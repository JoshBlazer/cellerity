package io.github.joshblazer.cellerity.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.DefinedName;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The tables in {@code docs/design/evaluator.md}, and evaluation across cells. */
class ReferenceSemanticsTest {
  private static Value eval(Map<String, Object> cells, String at) {
    return new WorkbookEvaluator(ParsedWorkbook.of(TestBooks.workbook(cells)))
        .value(0, CellRef.parse(at).orElseThrow());
  }

  private static Value num(double value) {
    return new Value.Num(value);
  }

  @Test
  void blankResultBecomesZero() {
    assertEquals(num(0), eval(Map.of("A2", "=A1"), "A2"));
  }

  @Test
  void blankConcatenatesAsEmptyText() {
    assertEquals(new Value.Text(""), eval(Map.of("A2", "=A1&\"\""), "A2"));
  }

  @Test
  void multiCellReferenceIntersectsWithTheFormulaRow() {
    Map<String, Object> cells = Map.of("A1", 1.0, "A2", 2.0, "A3", 3.0, "C2", "=A1:A3");
    assertEquals(num(2), eval(cells, "C2"));
  }

  @Test
  void multiCellReferenceOutsideTheFormulaRowIsAValueError() {
    Map<String, Object> cells = Map.of("A1", 1.0, "A2", 2.0, "C9", "=A1:A2");
    assertEquals(new Value.Err(ExcelError.VALUE), eval(cells, "C9"));
  }

  @Test
  void multiRowAndColumnReferenceIsAValueError() {
    assertEquals(new Value.Err(ExcelError.VALUE), eval(Map.of("C1", "=A1:B2"), "C1"));
  }

  @Test
  void multiCellReferenceInAnOperatorIntersectsToo() {
    Map<String, Object> cells = Map.of("A1", 1.0, "A2", 2.0, "C2", "=A1:A2*10");
    assertEquals(num(20), eval(cells, "C2"));
  }

  @Test
  void scalarFunctionIntersectsARange() {
    Map<String, Object> cells = Map.of("A1", -1.0, "A2", -2.0, "C2", "=ABS(A1:A2)");
    assertEquals(num(2), eval(cells, "C2"));
  }

  @Test
  void aChainOfFormulasEvaluates() {
    Map<String, Object> cells =
        Map.of("A1", 1.0, "A2", "=A1+1", "A3", "=A2+1", "A4", "=SUM(A1:A3)");
    assertEquals(num(6), eval(cells, "A4"));
  }

  @Test
  void aVeryLongChainDoesNotOverflow() {
    Map<String, Object> cells = new java.util.HashMap<>();
    cells.put("A1", 1.0);
    for (int i = 2; i <= 50_000; i++) {
      cells.put("A" + i, "=A" + (i - 1) + "+1");
    }
    assertEquals(num(50_000), eval(cells, "A50000"));
  }

  @Test
  void anUntakenBranchIsNotEvaluated() {
    assertEquals(num(1), eval(Map.of("A1", "=IF(TRUE,1,A1)"), "A1"));
  }

  @Test
  void definedNamesResolveLocalFirst() {
    var global = new DefinedName("Rate", "Sheet1!$A$1", OptionalInt.empty(), false);
    var local = new DefinedName("Rate", "Sheet1!$A$2", OptionalInt.of(0), false);
    var book =
        TestBooks.workbook(
            List.of(TestBooks.sheet("Sheet1", Map.of("A1", 1.0, "A2", 2.0, "B1", "=Rate"))),
            List.of(global, local));
    assertEquals(
        num(2),
        new WorkbookEvaluator(ParsedWorkbook.of(book)).value(0, CellRef.parse("B1").orElseThrow()));
  }

  @Test
  void relativeNamesMoveWithTheFormula() {
    // Stored relative to A1, so from C3 this name means two columns and two rows beyond B1.
    var name = new DefinedName("Moved", "Sheet1!B1", OptionalInt.empty(), false);
    var book =
        TestBooks.workbook(
            List.of(TestBooks.sheet("Sheet1", Map.of("B1", 1.0, "D3", 7.0, "C3", "=Moved"))),
            List.of(name));
    assertEquals(
        num(7),
        new WorkbookEvaluator(ParsedWorkbook.of(book)).value(0, CellRef.parse("C3").orElseThrow()));
  }

  @Test
  void crossSheetReferencesWork() {
    var book =
        TestBooks.workbook(
            List.of(
                TestBooks.sheet("Data", Map.of("A1", 5.0)),
                TestBooks.sheet("Calc", Map.of("A1", "=Data!A1*2"))),
            List.of());
    assertEquals(
        num(10),
        new WorkbookEvaluator(ParsedWorkbook.of(book)).value(1, CellRef.parse("A1").orElseThrow()));
  }
}
