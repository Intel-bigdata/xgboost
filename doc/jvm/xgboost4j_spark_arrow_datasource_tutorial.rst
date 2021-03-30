#####################################################
XGBoost4J-Spark With Arrow Datasource Tutorial
#####################################################


********************************************
Prerequisites
********************************************

Build Arrow Datasource Jar
===================================
You can refer `Unified Arrow Data Source <https://github.com/Intel-bigdata/OAP/blob/master/oap-data-source/arrow/README.md>`_  to compile the spark-arrow-datasource*.jar.

Build And Install Apache Arrow
===================================

.. code-block:: none

  // build arrow-cpp
  git clone --branch native-sql-engine-clean https://github.com/Intel-bigdata/arrow.git
  cd arrow/cpp
  mkdir build
  cd build
  cmake -DCMAKE_BUILD_TYPE=Release -DARROW_DEPENDENCY_SOURCE=BUNDLED -DARROW_PARQUET=ON -DARROW_HDFS=ON -DARROW_BOOST_USE_SHARED=ON -DARROW_JNI=ON -DARROW_WITH_SNAPPY=ON -DARROW_WITH_PROTOBUF=ON -DARROW_DATASET=ON ..
  make
  make install

Use Pre-built Arrow
===================================
Instead of building Apache Arrow from source, alternatively a much easier way is
to use a pre-built Arrow. For example, the following steps show how to install
Apache Arrow using conda:

.. code-block:: none
  conda install pyarrow=3.0.* -c conda-forge

Either building Arrow from source or using a pre-built binary, we need to set up ARROW_HOME and 
LD_LIBRARY_PATH environment variables before we build XGBoost:

.. code-block:: none

  export ARROW_HOME=/path/to/arrow/installation
  export LD_LIBRARY_PATH=$ARROW_HOME:$LD_LIBRARY_PATH

Building Java package from XGBoost Source Code
================================================

To avoid issues caused by complex dependences and mismatching system
libraries, the easiest way of building XGBoost is to do it in the same conda environment
where Arrow is installed (see above). We install essential build tools including
GCC, cmake, git and Maven in the environment, and then initiate the build
process from the same environment. The steps are:

.. code-block:: none

  conda install gcc_impl_linux-64=9.3.* maven git cmake -c conda-forge
  git clone https://github.com/Intel-bigdata/xgboost.git -b 5667+5774
  cd xgboost/jvm-packages
  mvn clean package -DskipTests 

Then you can get the ``xgboost*.jar`` and ``xgboost-spark*.jar`` in ``./xgboost4j-spark/target/`` and ``./xgboost4j/target/`` paths.

Download Spark 3.0.0
================================================
Currently xgboost spark with arrow datasource optimization works on the Spark 3.0.0 version.

.. code-block:: none

  wget http://archive.apache.org/dist/spark/spark-3.0.0/spark-3.0.0-bin-hadoop2.7.tgz
  tar -xf ./spark-3.0.0-bin-hadoop2.7.tgz
  export SPARK_HOME=``pwd``/spark-3.0.0-bin-hadoop2.7


********************************************
Get Started
********************************************
You can refer `Unified Arrow Data Source <https://github.com/Intel-bigdata/OAP/blob/master/oap-data-source/arrow/README.md>`_  to deploy the ``spark-arrow-datasource*.jar``. And the deploy approach of the ``xgboost*.jar`` and ``xgboost-spark*.jar`` is same with the upstream XGBoost without any additional operations.

********************************************
Note
********************************************
You don't need to use the ``VectorAssembler`` to assemble ``feature`` columns before training. Currently this optimization doesn't support ``limit``, ``coalesce`` and other sql operators, and we will support more operators in the future.
