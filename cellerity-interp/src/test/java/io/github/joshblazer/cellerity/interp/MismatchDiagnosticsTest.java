package io.github.joshblazer.cellerity.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.FormulaParser;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.SheetKind;
import io.github.joshblazer.cellerity.io.SheetState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Mismatch patterns go in public logs, so what they must not contain is tested here. */
class MismatchDiagnosticsTest {
  private static String pattern(String formula, Value expected, Value actual) {
    return MismatchPattern.of(FormulaParser.parse(formula).expr(), expected, actual);
  }

  @Test
  void aPatternHoldsNoContentFromTheWorkbook() {
    String pattern =
        pattern(
            "IF('Secret Sheet'!B7=\"Jane Doe\",SUM(Rates,$C$9:D12)*1234.5,MYSECRETFN(\"x\"))",
            new Value.Text("Jane Doe"),
            new Value.Text("Jane Do"));
    assertEquals("IF(XREF=STR,SUM(NAME,RANGE)*NUM,OTHERFN(STR)) | text vs text, differ", pattern);
  }

  @Test
  void aDifferenceHoldsNoValues() {
    assertEquals(
        "number vs number, gap below 1e-3, absolute above 1e-5",
        MismatchPattern.difference(new Value.Num(1000.5), new Value.Num(1000.6)));
    assertEquals(
        "number vs number, gap below 1e-6, absolute below 1e-7",
        MismatchPattern.difference(new Value.Num(0.1), new Value.Num(0.10000005)));
    assertEquals(
        "number vs number, opposite signs",
        MismatchPattern.difference(new Value.Num(-1), new Value.Num(1)));
    assertEquals(
        "number vs number, expected zero",
        MismatchPattern.difference(new Value.Num(0), new Value.Num(1)));
    assertEquals(
        "text vs text, differ by case",
        MismatchPattern.difference(new Value.Text("ABC"), new Value.Text("abc")));
    assertEquals(
        "text vs number", MismatchPattern.difference(new Value.Text("private"), new Value.Num(7)));
  }

  @Test
  void onlyTheFirstWrongCellIsARoot() {
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    put(cells, "A1", 1.0, null);
    put(cells, "B1", 99.0, "A1+1"); // wrong: the file says 99, it is 2
    put(cells, "C1", 100.0, "B1+1"); // wrong, but only because B1 differs
    put(cells, "D1", 300.0, "SUM(B1:C1)"); // wrong, and also downstream
    put(cells, "E1", 2.0, "A1+1"); // right
    Sheet sheet = new Sheet("Sheet1", SheetKind.WORKSHEET, SheetState.VISIBLE, cells);

    VerifyResult result =
        Verifier.verify(ParsedWorkbook.of(TestBooks.workbook(List.of(sheet), List.of())), 10);

    assertEquals(3, result.count(Category.REAL_MISMATCH));
    assertEquals(1, result.rootMismatches());
    assertEquals(Map.of("REF+NUM | number vs number, gap above 50%", 1L), result.rootPatterns());
    assertEquals(3, result.mismatchPatterns().values().stream().mapToLong(Long::longValue).sum());
    assertTrue(result.mismatches().get(0).root());
    assertFalse(result.mismatches().get(1).root());
  }

  @Test
  void aNameErrorFromAMissingAddInIsNotComparableButOthersStillCount() {
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    put(cells, "A1", 1.0, null);
    put(cells, "A2", 2.0, null);
    putError(cells, "B1", "ISODD(A1)"); // the add-in was missing when the file was saved
    putError(cells, "C1", "B1+1"); // and this only repeats that error
    putError(cells, "D1", "A1+A2"); // nothing explains this one
    Sheet sheet = new Sheet("Sheet1", SheetKind.WORKSHEET, SheetState.VISIBLE, cells);

    VerifyResult result =
        Verifier.verify(ParsedWorkbook.of(TestBooks.workbook(List.of(sheet), List.of())), 10);

    assertEquals(2, result.count(Category.NOT_COMPARABLE));
    assertEquals(
        Map.of("add-in not loaded when saved", 1L, "reads a cell whose add-in was not loaded", 1L),
        result.notComparable());
    assertEquals(1, result.count(Category.REAL_MISMATCH));
    assertEquals("D1", result.mismatches().get(0).cell());
  }

  @Test
  void aSolverAnswerIsJudgedByItsDocumentedAccuracy() {
    Value a = new Value.Num(0.123456789);
    assertEquals(
        Category.WITHIN_TOLERANCE,
        Comparison.compareSolver(a, new Value.Num(0.123456789 + 5e-9), 1e-8));
    assertEquals(
        Category.REAL_MISMATCH,
        Comparison.compareSolver(a, new Value.Num(0.123456789 + 5e-8), 1e-8));
    assertEquals(Category.REAL_MISMATCH, Comparison.compareSolver(a, new Value.Text("x"), 1e-8));
    assertEquals(Category.REAL_MISMATCH, Comparison.compare(a, new Value.Num(0.123456789 + 5e-9)));
  }

  @Test
  void valuesBelowTheNoiseFloorMatch() {
    assertEquals(
        Category.WITHIN_TOLERANCE,
        Comparison.compare(new Value.Num(6.1257e-17), new Value.Num(6.1232e-17)));
    assertEquals(
        Category.REAL_MISMATCH, Comparison.compare(new Value.Num(1e-14), new Value.Num(3e-14)));
  }

  @Test
  void countsSurviveAMerge() {
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    put(cells, "A1", 5.0, "1+1");
    Sheet sheet = new Sheet("Sheet1", SheetKind.WORKSHEET, SheetState.VISIBLE, cells);
    VerifyResult one =
        Verifier.verify(ParsedWorkbook.of(TestBooks.workbook(List.of(sheet), List.of())), 10);

    VerifyResult merged = new VerifyResult(0);
    merged.merge(one);
    merged.merge(one);

    assertEquals(2, merged.rootMismatches());
    assertEquals(2, merged.mismatchPatterns().values().iterator().next());
    assertTrue(merged.mismatches().isEmpty());
  }

  private static void putError(Map<CellRef, Cell> cells, String a1, String formula) {
    CellRef ref = CellRef.parse(a1).orElseThrow();
    cells.put(
        ref,
        new Cell(
            ref,
            Optional.of(new Value.Err(ExcelError.NAME)),
            Optional.of(
                new Formula(
                    Formula.Kind.NORMAL,
                    Optional.of(formula),
                    OptionalInt.empty(),
                    Optional.empty())),
            false));
  }

  private static void put(Map<CellRef, Cell> cells, String a1, double cached, String formula) {
    CellRef ref = CellRef.parse(a1).orElseThrow();
    Optional<Formula> stored =
        formula == null
            ? Optional.empty()
            : Optional.of(
                new Formula(
                    Formula.Kind.NORMAL,
                    Optional.of(formula),
                    OptionalInt.empty(),
                    Optional.empty()));
    cells.put(ref, new Cell(ref, Optional.of(new Value.Num(cached)), stored, false));
  }
}
