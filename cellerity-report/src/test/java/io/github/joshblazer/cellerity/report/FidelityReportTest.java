package io.github.joshblazer.cellerity.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.interp.Coverage;
import io.github.joshblazer.cellerity.interp.Verifier;
import io.github.joshblazer.cellerity.interp.VerifyResult;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.SheetKind;
import io.github.joshblazer.cellerity.io.SheetState;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.WorkbookInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class FidelityReportTest {
  private static final String HOSTILE = "<script>alert('x')</script>";

  private static ParsedWorkbook book() {
    CellRef a1 = CellRef.parse("A1").orElseThrow();
    Cell wrong =
        new Cell(
            a1,
            Optional.of(new Value.Text(HOSTILE)),
            Optional.of(
                new Formula(
                    Formula.Kind.NORMAL,
                    Optional.of("\"secret formula text\"&\"\""),
                    OptionalInt.empty(),
                    Optional.empty())),
            false);
    Sheet sheet = new Sheet(HOSTILE, SheetKind.WORKSHEET, SheetState.VISIBLE, Map.of(a1, wrong));
    return ParsedWorkbook.of(
        new Workbook(
            List.of(sheet),
            List.of(),
            DateSystem.SYSTEM_1900,
            new WorkbookInfo(Optional.empty(), Optional.empty(), Optional.empty(), false)));
  }

  private static String report(boolean showCells) {
    ParsedWorkbook book = book();
    VerifyResult result = Verifier.verify(book, 10);
    Coverage coverage = new Coverage();
    coverage.add(book);
    return FidelityReport.html("Report " + HOSTILE, result, coverage, showCells);
  }

  @Test
  void workbookTextIsEscaped() {
    String page = report(true);
    assertFalse(page.contains("<script"), page);
    assertTrue(page.contains("&lt;script&gt;"), page);
  }

  @Test
  void thePageIsSelfContained() {
    String page = report(true);
    assertFalse(page.contains("http:"), page);
    assertFalse(page.contains("https:"), page);
    assertFalse(page.contains("src="), page);
    assertFalse(page.contains("href="), page);
  }

  @Test
  void anAggregateReportLeavesOutCellsAndFormulas() {
    String page = report(false);
    assertFalse(page.contains("secret formula text"), page);
    assertFalse(page.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</td><td>A1"), page);
    assertFalse(page.contains("Mismatched cells"), page);
    assertTrue(page.contains("All mismatches"), page);
  }

  @Test
  void fidelityIsNeverShownWithoutCoverage() {
    String page = report(false);
    assertTrue(page.contains("Fidelity 0.0000%"), page);
    assertTrue(page.contains("Coverage 100.0000%"), page);
  }
}
