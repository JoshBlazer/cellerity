package io.github.joshblazer.cellerity.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes the benchmark's workbook. It is synthetic, and shaped like a small pricing model: an input
 * sheet and a lookup table, formula columns that repeat down rows, and summaries at the bottom. It
 * says nothing about workbooks of other shapes.
 */
final class ModelBook {
  /** How many numeric inputs the model has, in {@code Inputs!A1:A200}. */
  static final int INPUTS = 200;

  /** Formula cells per row of the {@code Calc} sheet. */
  static final int COLUMNS = 5;

  /** Summary formulas below the rows. */
  static final int SUMMARIES = 3;

  private ModelBook() {}

  /** Writes the model to a new file, with values calculated by POI so that it has cached values. */
  static Path write(int rows) throws IOException {
    Path file = Files.createTempFile("cellerity-model-" + rows + "-", ".xlsx");
    try (XSSFWorkbook book = new XSSFWorkbook();
        OutputStream out = Files.newOutputStream(file)) {
      Sheet inputs = book.createSheet("Inputs");
      for (int i = 0; i < INPUTS; i++) {
        Row row = inputs.createRow(i);
        row.createCell(0).setCellValue(100.0 + i);
        row.createCell(2).setCellValue(i + 1.0);
        row.createCell(3).setCellValue(1.0 + i / 400.0);
      }
      inputs.getRow(0).createCell(4).setCellValue(0.035);
      Sheet calc = book.createSheet("Calc");
      for (int i = 1; i <= rows; i++) {
        Row row = calc.createRow(i - 1);
        int key = i % INPUTS + 1;
        row.createCell(0).setCellFormula("Inputs!$A$" + key + "*(1+Inputs!$E$1)");
        row.createCell(1).setCellFormula("IF(A" + i + ">200,A" + i + "*0.9,A" + i + "*1.1)");
        row.createCell(2).setCellFormula("VLOOKUP(" + key + ",Inputs!$C$1:$D$200,2,FALSE)");
        row.createCell(3).setCellFormula("ROUND(B" + i + "*C" + i + ",2)");
        row.createCell(4).setCellFormula("D" + i + "-A" + i + "+SUM(A" + i + ":C" + i + ")/3");
      }
      Row summary = calc.createRow(rows);
      summary.createCell(0).setCellFormula("SUM(E1:E" + rows + ")");
      summary.createCell(1).setCellFormula("AVERAGE(D1:D" + rows + ")");
      summary.createCell(2).setCellFormula("MAX(A" + (rows + 1) + ",B" + (rows + 1) + ")");
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.write(out);
    }
    return file;
  }
}
