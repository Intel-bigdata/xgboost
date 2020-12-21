/*
 Copyright (c) 2014 by Contributors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package ml.dmlc.xgboost4j.scala.spark

import java.util.Objects

import ml.dmlc.xgboost4j.java.arrow.ArrowRecordBatchHandle
import ml.dmlc.xgboost4j.java.util.UtilReflection
import ml.dmlc.xgboost4j.{LabeledPoint => XGBLabeledPoint}
import org.apache.arrow.vector.ValueVector
import org.apache.spark.HashPartitioner
import org.apache.spark.ml.feature.{LabeledPoint => MLLabeledPoint}
import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector, Vectors}
import org.apache.spark.ml.param.Param
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.adaptive.{AdaptiveExecutionContext, InsertAdaptiveSparkPlan}
import org.apache.spark.sql.execution.dynamicpruning.PlanDynamicPruningFilters
import org.apache.spark.sql.execution.exchange.{EnsureRequirements, ReuseExchange}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{FloatType, IntegerType}
import org.apache.spark.sql.vectorized.ColumnarBatch

import scala.collection.mutable.ListBuffer

object DataUtils extends Serializable {
  private[spark] implicit class XGBLabeledPointFeatures(
      val labeledPoint: XGBLabeledPoint
  ) extends AnyVal {
    /** Converts the point to [[MLLabeledPoint]]. */
    private[spark] def asML: MLLabeledPoint = {
      MLLabeledPoint(labeledPoint.label, labeledPoint.features)
    }

    /**
     * Returns feature of the point as [[org.apache.spark.ml.linalg.Vector]].
     */
    def features: Vector = if (labeledPoint.indices == null) {
      Vectors.dense(labeledPoint.values.map(_.toDouble))
    } else {
      Vectors.sparse(labeledPoint.size, labeledPoint.indices, labeledPoint.values.map(_.toDouble))
    }
  }

  private[spark] implicit class MLLabeledPointToXGBLabeledPoint(
      val labeledPoint: MLLabeledPoint
  ) extends AnyVal {
    /** Converts an [[MLLabeledPoint]] to an [[XGBLabeledPoint]]. */
    def asXGB: XGBLabeledPoint = {
      labeledPoint.features.asXGB.copy(label = labeledPoint.label.toFloat)
    }
  }

  private[spark] implicit class MLVectorToXGBLabeledPoint(val v: Vector) extends AnyVal {
    /**
     * Converts a [[Vector]] to a data point with a dummy label.
     *
     * This is needed for constructing a [[ml.dmlc.xgboost4j.scala.DMatrix]]
     * for prediction.
     */
    def asXGB: XGBLabeledPoint = v match {
      case v: DenseVector =>
        XGBLabeledPoint(0.0f, v.size, null, v.values.map(_.toFloat))
      case v: SparseVector =>
        XGBLabeledPoint(0.0f, v.size, v.indices, v.values.map(_.toFloat))
    }
  }

  private def attachPartitionKey(
      row: Row,
      deterministicPartition: Boolean,
      numWorkers: Int,
      xgbLp: XGBLabeledPoint): (Int, XGBLabeledPoint) = {
    if (deterministicPartition) {
      (math.abs(row.hashCode() % numWorkers), xgbLp)
    } else {
      (1, xgbLp)
    }
  }

  private def repartitionRDDs(
      deterministicPartition: Boolean,
      numWorkers: Int,
      arrayOfRDDs: Array[RDD[(Int, XGBLabeledPoint)]]): Array[RDD[XGBLabeledPoint]] = {
    if (deterministicPartition) {
      arrayOfRDDs.map {rdd => rdd.partitionBy(new HashPartitioner(numWorkers))}.map {
        rdd => rdd.map(_._2)
      }
    } else {
      arrayOfRDDs.map(rdd => {
        val nPartitions = if (rdd.sparkContext.isLocal) numWorkers else {
          if (rdd.getNumPartitions % numWorkers != 0) {
            rdd.getNumPartitions + numWorkers - (rdd.getNumPartitions % numWorkers)
          } else {
            rdd.getNumPartitions
          }
        }
        if (rdd.getNumPartitions != nPartitions) {
          rdd.map(_._2).repartition(nPartitions)
        } else {
          rdd.map(_._2)
        }
      })
    }
  }

  private[spark] def convertDataFrameToXGBLabeledPointRDDs(
      labelCol: Column,
      featuresCol: Column,
      weight: Column,
      baseMargin: Column,
      group: Option[Column],
      numWorkers: Int,
      deterministicPartition: Boolean,
      dataFrames: DataFrame*): Array[RDD[XGBLabeledPoint]] = {
    val selectedColumns = group.map(groupCol => Seq(labelCol.cast(FloatType),
      featuresCol,
      weight.cast(FloatType),
      groupCol.cast(IntegerType),
      baseMargin.cast(FloatType))).getOrElse(Seq(labelCol.cast(FloatType),
      featuresCol,
      weight.cast(FloatType),
      baseMargin.cast(FloatType)))
    val arrayOfRDDs = dataFrames.toArray.map {
      df => df.select(selectedColumns: _*).rdd.map {
        case row @ Row(label: Float, features: Vector, weight: Float, group: Int,
          baseMargin: Float) =>
          val (size, indices, values) = features match {
            case v: SparseVector => (v.size, v.indices, v.values.map(_.toFloat))
            case v: DenseVector => (v.size, null, v.values.map(_.toFloat))
          }
          val xgbLp = XGBLabeledPoint(label, size, indices, values, weight, group, baseMargin)
          attachPartitionKey(row, deterministicPartition, numWorkers, xgbLp)
        case row @ Row(label: Float, features: Vector, weight: Float, baseMargin: Float) =>
          val (size, indices, values) = features match {
            case v: SparseVector => (v.size, v.indices, v.values.map(_.toFloat))
            case v: DenseVector => (v.size, null, v.values.map(_.toFloat))
          }
          val xgbLp = XGBLabeledPoint(label, size, indices, values, weight, baseMargin = baseMargin)
          attachPartitionKey(row, deterministicPartition, numWorkers, xgbLp)
      }
    }
    repartitionRDDs(deterministicPartition, numWorkers, arrayOfRDDs)
  }

  private[spark] def convertDataFrameToArrowRecordBatchRDDs(
      labelCol: Column,
      numWorkers: Int,
      deterministicPartition: Boolean,
      dataFrames: DataFrame*): Array[(RDD[ArrowRecordBatchHandle], Int)] = {

    val arrayOfRDDs = dataFrames.toArray.map {
      df => {
        val qe = new QueryExecution(df.sparkSession, df.queryExecution.logical) {
          override protected def preparations: Seq[Rule[SparkPlan]] = {
            Seq(
              InsertAdaptiveSparkPlan(AdaptiveExecutionContext(sparkSession, this)),
              PlanDynamicPruningFilters(sparkSession),
              PlanSubqueries(sparkSession),
              EnsureRequirements(sparkSession.sessionState.conf),
              CollapseCodegenStages(sparkSession.sessionState.conf),
              ReuseExchange(sparkSession.sessionState.conf),
              ReuseSubquery(sparkSession.sessionState.conf)
            )
          }
        }

        val plan = qe.executedPlan
        val labelArray = plan.schema.fields.zipWithIndex.filter {
          case (f, i) => {
            if (Objects.equals(f.name, labelCol.toString())) true else false
          }
        }
        if (labelArray.length == 0) {
          throw new IllegalArgumentException("label column not found")
        }
        if (labelArray.length != 1) {
          throw new IllegalArgumentException("clashed label column, aborting")
        }

        val rdd: RDD[ColumnarBatch] = plan.executeColumnar()
        (rdd.map {
          batch => {
            val fields = ListBuffer[ArrowRecordBatchHandle.Field]()
            val buffers = ListBuffer[ArrowRecordBatchHandle.Buffer]()
            val dataTypes = ListBuffer[String]()
            for (i <- 0 until batch.numCols()) {
              val vector = batch.column(i)
              val accessor = UtilReflection.getField(vector, "accessor")
              val valueVector = UtilReflection.getField(accessor, "accessor")
                .asInstanceOf[ValueVector]
              val bufs = valueVector.getBuffers(false);
              fields.append(new ArrowRecordBatchHandle.Field(bufs.length,
                valueVector.getNullCount))
              dataTypes.append(vector.dataType().typeName)
              for (buf <- bufs) {
                buf.retain()
                buffers.append(new ArrowRecordBatchHandle.Buffer(buf.memoryAddress(),
                  buf.getReferenceManager.getSize, buf.getReferenceManager.getSize,
                  buf.getReferenceManager))
              }

            }
            new ArrowRecordBatchHandle(batch.numRows(), dataTypes.toArray,
              fields.toArray, buffers.toArray, batch)
          }
        }, labelArray(0)._2)
      }
    }
    arrayOfRDDs
  }

}
