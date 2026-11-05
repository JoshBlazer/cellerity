package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.DefinedName;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.SheetKind;
import io.github.joshblazer.cellerity.io.SheetState;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.WorkbookInfo;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.EOFRecord;
import org.apache.poi.hssf.record.RecordInputStream;
import org.apache.poi.hssf.record.TableRecord;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Reads legacy {@code .xls} corpus files into our workbook model, using POI's HSSF reader. Corpus
 * use only (ADR-0013): the published library reads {@code .xlsx} alone, and POI's evaluator is
 * never called.
 *
 * <p>Formula text here is POI's rendering of Excel's binary tokens, not text Excel stored, so a
 * parse rate on this corpus partly measures that rendering. Cached values are the ones Excel
 * stored.
 */
final class HssfWorkbooks {
  private HssfWorkbooks() {}

  /** Reads a file. Any failure in POI, including on a misnamed file, is a typed rejection. */
  static Workbook read(Path file) throws XlsxReadException {
    try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true);
        HSSFWorkbook workbook = new HSSFWorkbook(fs.getRoot(), true)) {
      return convert(workbook, dataTables(fs));
    } catch (IOException | RuntimeException e) {
      throw new XlsxReadException(
          XlsxReadException.Kind.UNSUPPORTED_FORMAT,
          "Not readable as .xls: " + e.getClass().getSimpleName(),
          e);
    }
  }

  /**
   * The data-table ranges of each sheet, by sheet position, from the raw {@code TABLE} records.
   * POI's usermodel throws when asked for the formula of such a cell, as if it were a broken array
   * formula.
   */
  private static Map<Integer, List<CellRangeAddress>> dataTables(POIFSFileSystem fs)
      throws IOException {
    Map<Integer, List<CellRangeAddress>> tables = new HashMap<>();
    String stream = fs.getRoot().hasEntry("Workbook") ? "Workbook" : "Book";
    try (InputStream in = fs.createDocumentInputStream(stream)) {
      RecordInputStream records = new RecordInputStream(in);
      int sheet = -1;
      int depth = 0;
      while (records.hasNextRecord()) {
        records.nextRecord();
        switch (records.getSid()) {
          case BOFRecord.sid -> {
            records.readShort();
            if (depth++ == 0 && records.readShort() != BOFRecord.TYPE_WORKBOOK) {
              sheet++;
            }
          }
          case EOFRecord.sid -> depth--;
          case TableRecord.sid -> {
            int firstRow = records.readUShort();
            int lastRow = records.readUShort();
            int firstColumn = records.readUByte();
            int lastColumn = records.readUByte();
            tables
                .computeIfAbsent(sheet, s -> new ArrayList<>())
                .add(new CellRangeAddress(firstRow, lastRow, firstColumn, lastColumn));
          }
          default -> {}
        }
        records.readRemainder();
      }
    }
    return tables;
  }

  private static Workbook convert(
      HSSFWorkbook workbook, Map<Integer, List<CellRangeAddress>> tables) {
    List<Sheet> sheets = new ArrayList<>();
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      HSSFSheet sheet = workbook.getSheetAt(i);
      SheetState state =
          workbook.isSheetVeryHidden(i)
              ? SheetState.VERY_HIDDEN
              : workbook.isSheetHidden(i) ? SheetState.HIDDEN : SheetState.VISIBLE;
      sheets.add(
          new Sheet(
              sheet.getSheetName(),
              SheetKind.WORKSHEET,
              state,
              cells(sheet, tables.getOrDefault(i, List.of()))));
    }
    SummaryInformation summary = workbook.getSummaryInformation();
    Optional<String> writer =
        Optional.ofNullable(summary).map(SummaryInformation::getApplicationName).map(String::strip);
    WorkbookInfo info =
        new WorkbookInfo(
            writer.filter(s -> !s.isEmpty()),
            Optional.empty(),
            Optional.empty(),
            workbook.getForceFormulaRecalculation());
    DateSystem dates =
        workbook.getInternalWorkbook().isUsing1904DateWindowing()
            ? DateSystem.SYSTEM_1904
            : DateSystem.SYSTEM_1900;
    return new Workbook(sheets, names(workbook), dates, info);
  }

  private static List<DefinedName> names(HSSFWorkbook workbook) {
    List<DefinedName> names = new ArrayList<>();
    for (Name name : workbook.getAllNames()) {
      if (name.isFunctionName()) {
        continue;
      }
      String refersTo;
      try {
        refersTo = name.getRefersToFormula();
      } catch (RuntimeException e) {
        refersTo = "#REF!";
      }
      int sheet = name.getSheetIndex();
      names.add(
          new DefinedName(
              name.getNameName(),
              refersTo == null ? "#REF!" : refersTo,
              sheet >= 0 ? OptionalInt.of(sheet) : OptionalInt.empty(),
              false));
    }
    return names;
  }

  private static Map<CellRef, Cell> cells(HSSFSheet sheet, List<CellRangeAddress> tables) {
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    for (Row row : sheet) {
      for (org.apache.poi.ss.usermodel.Cell raw : row) {
        HSSFCell cell = (HSSFCell) raw;
        CellRef ref = new CellRef(cell.getColumnIndex() + 1, cell.getRowIndex() + 1);
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
          continue;
        }
        Optional<Formula> formula = Optional.empty();
        if (type == CellType.FORMULA) {
          formula = formula(cell, tables);
          type = cell.getCachedFormulaResultType();
        }
        Optional<Value> value = value(cell, type);
        if (value.isPresent() || formula.isPresent()) {
          cells.put(ref, new Cell(ref, value, formula, dateFormatted(cell, type)));
        }
      }
    }
    return Collections.unmodifiableMap(cells);
  }

  /** The formula, or empty for a non-master array member, which stores only its value. */
  private static Optional<Formula> formula(HSSFCell cell, List<CellRangeAddress> tables) {
    for (CellRangeAddress table : tables) {
      if (table.isInRange(cell)) {
        return Optional.of(
            new Formula(
                Formula.Kind.DATA_TABLE,
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(a1(table))));
      }
    }
    String text;
    try {
      text = cell.getCellFormula();
    } catch (RuntimeException e) {
      text = "";
    }
    Optional<String> stored = text.isEmpty() ? Optional.empty() : Optional.of(text);
    if (cell.isPartOfArrayFormulaGroup()) {
      CellRangeAddress range = cell.getArrayFormulaRange();
      if (range.getFirstRow() != cell.getRowIndex()
          || range.getFirstColumn() != cell.getColumnIndex()) {
        return Optional.empty();
      }
      return Optional.of(
          new Formula(Formula.Kind.ARRAY, stored, OptionalInt.empty(), Optional.of(a1(range))));
    }
    return Optional.of(
        new Formula(Formula.Kind.NORMAL, stored, OptionalInt.empty(), Optional.empty()));
  }

  private static String a1(CellRangeAddress range) {
    return new CellRef(range.getFirstColumn() + 1, range.getFirstRow() + 1).toA1()
        + ":"
        + new CellRef(range.getLastColumn() + 1, range.getLastRow() + 1).toA1();
  }

  private static Optional<Value> value(HSSFCell cell, CellType type) {
    return switch (type) {
      case NUMERIC -> Optional.of(new Value.Num(cell.getNumericCellValue()));
      case STRING -> Optional.of(new Value.Text(cell.getStringCellValue()));
      case BOOLEAN -> Optional.of(new Value.Bool(cell.getBooleanCellValue()));
      case ERROR ->
          ExcelError.fromCode(FormulaError.forInt(cell.getErrorCellValue()).getString())
              .map(Value.Err::new);
      default -> Optional.empty();
    };
  }

  private static boolean dateFormatted(HSSFCell cell, CellType type) {
    if (type != CellType.NUMERIC) {
      return false;
    }
    try {
      return DateUtil.isCellDateFormatted(cell);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
