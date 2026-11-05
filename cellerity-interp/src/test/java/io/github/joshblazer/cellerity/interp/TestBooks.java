package io.github.joshblazer.cellerity.interp;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** Builds workbooks in memory. A string starting with {@code =} is a formula. */
final class TestBooks {
  private TestBooks() {}

  /** The fixture used by {@code function-cases.tsv}. */
  static Map<String, Object> fixture() {
    Map<String, Object> cells = new LinkedHashMap<>();
    for (int i = 1; i <= 5; i++) {
      cells.put("A" + i, (double) i);
      cells.put("B" + i, i * 10.0);
    }
    cells.put("C1", "apple");
    cells.put("C2", "banana");
    cells.put("C3", "cherry");
    cells.put("D1", true);
    cells.put("D2", "3");
    // D3 is blank.
    cells.put("H1", ExcelError.DIV0);
    for (int i = 1; i <= 4; i++) {
      cells.put("F" + i, (double) i);
      cells.put("G" + i, String.valueOf((char) ('a' + i - 1)));
    }
    return cells;
  }

  static Sheet sheet(String name, Map<String, Object> cells) {
    Map<CellRef, Cell> built = new LinkedHashMap<>();
    cells.forEach((a1, content) -> {
      CellRef ref = CellRef.parse(a1).orElseThrow();
      built.put(ref, cell(ref, content));
    });
    return new Sheet(name, SheetKind.WORKSHEET, SheetState.VISIBLE, built);
  }

  static Workbook workbook(List<Sheet> sheets, List<DefinedName> names) {
    return new Workbook(
        sheets,
        names,
        DateSystem.SYSTEM_1900,
        new WorkbookInfo(Optional.empty(), Optional.empty(), Optional.empty(), false));
  }

  static Workbook workbook(Map<String, Object> cells) {
    return workbook(List.of(sheet("Sheet1", cells)), List.of());
  }

  private static Cell cell(CellRef ref, Object content) {
    return switch (content) {
      case String s when s.startsWith("=") ->
          new Cell(
              ref,
              Optional.empty(),
              Optional.of(
                  new Formula(
                      Formula.Kind.NORMAL,
                      Optional.of(s.substring(1)),
                      OptionalInt.empty(),
                      Optional.empty())),
              false);
      case String s -> stored(ref, new Value.Text(s));
      case Double d -> stored(ref, new Value.Num(d));
      case Boolean b -> stored(ref, new Value.Bool(b));
      case ExcelError e -> stored(ref, new Value.Err(e));
      default -> throw new IllegalArgumentException(String.valueOf(content));
    };
  }

  private static Cell stored(CellRef ref, Value value) {
    return new Cell(ref, Optional.of(value), Optional.empty(), false);
  }
}
