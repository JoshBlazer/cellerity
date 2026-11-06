package io.github.joshblazer.cellerity.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
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
        "number vs number, gap below 1e-3",
        MismatchPattern.difference(new Value.Num(1000.5), new Value.Num(1000.6)));
    assertEquals(
        "number vs number, opposite signs",
        MismatchPattern.difference(new Value.Num(-1), new Value.Num(1)));
    assertEquals(
        "number vs number, one is zero",
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
