#include <iostream>
#include <vector>
#include <algorithm>
#include "data/adapter.h"
#include "arrow/api.h"
#include "arrow/filesystem/filesystem.h"
#include "parquet/arrow/reader.h"

const char* input_file = "/home/zhang/data/part-00000-aaf240ad-65cb-4ed9-bd05-1f11de010af2-c000.snappy.parquet";
const char* label_name = "delinquency_12";

arrow::Status DMatrixFromParquet(const char* path, std::shared_ptr<xgboost::DMatrix>& dmat)
{
  std::shared_ptr<arrow::fs::FileSystem> fs;
  ARROW_ASSIGN_OR_RAISE(fs, arrow::fs::FileSystemFromUri("file:///"));

  std::shared_ptr<arrow::io::RandomAccessFile> infile;
  ARROW_ASSIGN_OR_RAISE(infile, fs->OpenInputFile(path));

  std::unique_ptr<parquet::arrow::FileReader> arrow_reader;
  ARROW_RETURN_NOT_OK(parquet::arrow::OpenFile(
        infile, arrow::default_memory_pool(), &arrow_reader));

  std::shared_ptr<arrow::Table> table;
  ARROW_RETURN_NOT_OK(arrow_reader->ReadTable(&table));

  // table sizes
  size_t nrow{static_cast<size_t>(table->num_rows())}, ncol{static_cast<size_t>(table->num_columns())};

  // get the label column
  xgboost::data::TableColumn label_col;
  auto label_idx = std::numeric_limits<size_t>::max();
  std::vector<std::string> col_names = table->ColumnNames();
  for (auto i = 0; i < col_names.size(); ++i) {
    if (col_names[i] == label_name) {
      label_idx = i;
      break;
    }
  }
  if (label_idx >= col_names.size()) {
    return arrow::Status::IndexError("No label column found");
  }
  label_col = table->column(label_idx);
  ARROW_ASSIGN_OR_RAISE(table, table->RemoveColumn(label_idx));
  ncol--;
      
  // Create DMatrix
  arrow::TableBatchReader treader{*table};
  xgboost::data::RecordBatches rb;
  ARROW_RETURN_NOT_OK(treader.ReadAll(&rb));
  xgboost::data::ArrowAdapter adapter(rb, label_col, nrow, ncol, -1);
  dmat.reset(xgboost::DMatrix::Create(&adapter, 0, -1));

  return arrow::Status::OK();
}

int main()
{
  auto print = [](const auto& val) { std::cout << val << ' '; };
  auto print_entry = [](const xgboost::Entry& val) { std::cout << val.fvalue << ','; };

  std::shared_ptr<xgboost::DMatrix> dmat;
  if (DMatrixFromParquet(input_file, dmat).ok()) {
    // labels 
    auto& labels = dmat->Info().labels_.HostVector();
    std::for_each(labels.begin(), labels.end(), print);
    std::cout << "\n";
    // row batch (SimpleDMatrix has only a single batch)
    auto batch = dmat->GetBatches<xgboost::SparsePage>().begin();
    const auto& page = *batch;
    // row offsets
    const auto& row_offsets = page.offset.HostVector();
    std::for_each(row_offsets.begin(), row_offsets.end(), print);
    std::cout << "\n";
    // row values
    const auto& all_entries = page.data.HostVector();
    auto it = all_entries.begin();
    for (int i = 0; i < dmat->Info().num_row_; ++i) {
      std::for_each(it, it + dmat->Info().num_col_, print_entry);
      std::advance(it, dmat->Info().num_col_);
      std::cout << "\n";
    }
  }
}

