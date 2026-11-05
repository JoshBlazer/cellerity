package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import io.github.joshblazer.cellerity.io.XlsxReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Compares stored values and formula text between our reader and Apache POI. POI is used only as a
 * second reader of the file: its formula evaluator is never called (ADR-0011).
 *
 * <p>Agreement shows the reader decodes files the way another mature reader does. It says nothing
 * about Excel fidelity, which needs an evaluator.
 *
 * <p>Usage: {@code PoiCrossCheck DIR [DIR ...]}. Exit 0 if there are no disagreements, otherwise 1.
 */
public final class PoiCrossCheck {
  private static final int MAX_EXAMPLES = 40;
  private static final int MIN_FILES_COMPARED = 300;

  private final Map<String, Integer> disagreements = new TreeMap<>();
  private final List<String> examples = new ArrayList<>();
  private int filesCompared;
  private int rejectedByUs;
  private int rejectedByPoi;
  private long cellsCompared;
  private long arrayMemberCells;
  private long emptyStoredNumbers;

  private PoiCrossCheck() {}

  public static void main(String[] args) throws IOException {
    PoiCrossCheck check = new PoiCrossCheck();
    for (String root : args) {
      List<Path> files;
      try (Stream<Path> walk = Files.walk(Path.of(root))) {
        files =
            walk.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                .sorted()
                .toList();
      }
      for (Path file : files) {
        check.compareFile(file);
      }
    }
    System.out.printf(
        Locale.ROOT,
        "cross-check: %d files compared, %d rejected by us, %d rejected by POI, %d cells compared%n"
            + "explained differences: %d array-member cells, %d empty stored numbers%n"
            + "disagreements: %s%n",
        check.filesCompared,
        check.rejectedByUs,
        check.rejectedByPoi,
        check.cellsCompared,
        check.arrayMemberCells,
        check.emptyStoredNumbers,
        check.disagreements);
    check.examples.forEach(System.out::println);
    // Agreement over too few files proves little, so a drop in coverage is a failure too.
    boolean enoughCoverage = check.filesCompared >= MIN_FILES_COMPARED;
    if (!enoughCoverage) {
      System.out.println("FAILED: fewer than " + MIN_FILES_COMPARED + " files compared");
    }
    System.exit(check.disagreements.isEmpty() && enoughCoverage ? 0 : 1);
  }

  private void compareFile(Path file) {
    Workbook ours;
    try {
      ours = XlsxReader.read(file);
    } catch (XlsxReadException e) {
      rejectedByUs++;
      System.out.println("Reader rejected " + file.getFileName() + ": " + e.kind());
      return;
    }
    // POI can reject a file for its own reasons, such as its zip-bomb heuristic. Any failure means
    // there is nothing to compare with, not that either reader is wrong.
    // Read-only access is essential: new XSSFWorkbook(File) saves over the file on close, which
    // once damaged 320 corpus files until the corpus checksums caught it.
    try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
        XSSFWorkbook poi = new XSSFWorkbook(pkg)) {
      for (int i = 0; i < poi.getNumberOfSheets(); i++) {
        XSSFSheet sheet = poi.getSheetAt(i);
        Optional<Sheet> mine = ours.sheet(sheet.getSheetName());
        if (mine.isEmpty()) {
          report("sheet-missing", file, sheet.getSheetName(), "");
          continue;
        }
        compareSheet(file, sheet, mine.get());
        findCellsOnlyWeHave(file, sheet, mine.get());
      }
      filesCompared++;
      pkg.revert();
    } catch (Exception | LinkageError e) {
      rejectedByPoi++;
      System.out.println(
          "POI could not read " + file.getFileName() + ": " + e.getClass().getSimpleName());
    }
  }

  private void compareSheet(Path file, XSSFSheet poiSheet, Sheet mine) {
    for (Row row : poiSheet) {
      for (org.apache.poi.ss.usermodel.Cell raw : row) {
        XSSFCell cell = (XSSFCell) raw;
        compareCell(file, poiSheet.getSheetName(), cell, mine);
      }
    }
  }

  /** Finds cells we stored that POI does not see, which comparing POI's cells cannot reveal. */
  private void findCellsOnlyWeHave(Path file, XSSFSheet poiSheet, Sheet mine) {
    for (CellRef ref : mine.cells().keySet()) {
      Row row = poiSheet.getRow(ref.row() - 1);
      org.apache.poi.ss.usermodel.Cell cell = row == null ? null : row.getCell(ref.column() - 1);
      if (cell == null || cell.getCellType() == CellType.BLANK) {
        report("cell-only-ours", file, poiSheet.getSheetName() + "!" + ref.toA1(), "");
      }
    }
  }

  private void compareCell(Path file, String sheetName, XSSFCell cell, Sheet mine) {
    CellRef ref = new CellRef(cell.getColumnIndex() + 1, cell.getRowIndex() + 1);
    String where = sheetName + "!" + ref.toA1();
    Optional<io.github.joshblazer.cellerity.io.Cell> found = mine.cell(ref);
    CellType type = cell.getCellType();
    if (type == CellType.BLANK) {
      return;
    }
    cellsCompared++;
    if (found.isEmpty()
        && type == CellType.NUMERIC
        && !cell.getCTCell().isSetF()
        && cell.getCTCell().isSetV()
        && cell.getCTCell().getV().isEmpty()) {
      // <c t="n"><v></v></c> stores nothing. POI reports 0, and we report blank.
      emptyStoredNumbers++;
      return;
    }
    if (found.isEmpty()) {
      boolean emptyText = type == CellType.STRING && cell.getStringCellValue().isEmpty();
      if (!emptyText) {
        report("cell-missing", file, where, "POI type " + type);
      }
      return;
    }
    io.github.joshblazer.cellerity.io.Cell ours = found.get();
    if (type == CellType.FORMULA) {
      compareFormulaText(file, where, cell, ours);
      type = cell.getCachedFormulaResultType();
    } else if (ours.formula().isPresent()) {
      report("formula-only-ours", file, where, "POI type " + type);
    }
    Optional<Value> value = ours.value();
    if (value.isEmpty()) {
      // A formula with no stored value: POI reports a default, which is not a stored value.
      return;
    }
    String theirs = poiValue(cell, type);
    String mineText = ourValue(value.get());
    if (!theirs.equals(mineText)) {
      report("value", file, where, "ours [" + mineText + "] POI [" + theirs + "]");
    }
  }

  private void compareFormulaText(
      Path file, String where, XSSFCell cell, io.github.joshblazer.cellerity.io.Cell ours) {
    Optional<Formula> formula = ours.formula();
    if (formula.isEmpty() && cell.isPartOfArrayFormulaGroup()) {
      // Only the master of an array range stores <f>. Cached values are still compared.
      arrayMemberCells++;
      return;
    }
    if (formula.isEmpty()) {
      report("formula-missing", file, where, "POI [" + cell.getCellFormula() + "]");
      return;
    }
    // POI expands shared formulas, and our followers carry no text until M1.
    Optional<String> text = formula.get().text();
    if (formula.get().kind() == Formula.Kind.NORMAL && text.isPresent()) {
      String theirs;
      try {
        theirs = cell.getCellFormula();
      } catch (RuntimeException e) {
        return;
      }
      if (!theirs.equals(text.get())) {
        report("formula-text", file, where, "ours [" + text.get() + "] POI [" + theirs + "]");
      }
    }
  }

  private static String poiValue(XSSFCell cell, CellType type) {
    return switch (type) {
      case NUMERIC -> "n:" + cell.getNumericCellValue();
      case STRING -> "s:" + cell.getStringCellValue();
      case BOOLEAN -> "b:" + cell.getBooleanCellValue();
      case ERROR -> "e:" + cell.getErrorCellString();
      default -> "other:" + type;
    };
  }

  private static String ourValue(Value value) {
    return switch (value) {
      case Value.Num num -> "n:" + num.value();
      case Value.Text text -> "s:" + text.value();
      case Value.Bool bool -> "b:" + bool.value();
      case Value.Err error -> "e:" + errorCode(error.error());
      case Value.Blank blank -> "blank";
    };
  }

  private static String errorCode(ExcelError error) {
    return error.code();
  }

  private void report(String category, Path file, String where, String detail) {
    disagreements.merge(category, 1, Integer::sum);
    if (examples.size() < MAX_EXAMPLES) {
      examples.add(category + " " + file.getFileName() + " " + where + " " + detail);
    }
  }
}
