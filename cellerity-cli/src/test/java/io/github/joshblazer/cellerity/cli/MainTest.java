package io.github.joshblazer.cellerity.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {
  private static final String SAMPLE = "../corpus/samples/synthetic/sample.xlsx";

  private record Result(int exit, String out, String err) {}

  private static Result run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(
        exit,
        out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"),
        err.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
  }

  @ParameterizedTest(name = "--get {0} prints [{1}]")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "Sheet1!A1|42", // M0 exit check
        "sheet1!a1|42",
        "Sheet1!$A$1|42",
        "Sheet1!B1|50000",
        "Sheet1!B2|1750",
        "Sheet1!A2|Premium model",
        "Sheet1!A3|TRUE",
        "Sheet1!B3|#DIV/0!",
        "Rates & Terms!B1|0.035",
        "'Rates & Terms'!B1|0.035",
        "CoverAmount|50000",
        "coveramount|50000",
        "AnnualRate|0.035",
      })
  void getPrintsTheCalculatedValue(String ref, String expected) {
    Result r = run("eval", SAMPLE, "--get", ref);
    assertEquals(0, r.exit(), r.err());
    assertEquals(expected + "\n", r.out());
  }

  @Test
  void blankCellPrintsAnEmptyLine() {
    Result r = run("eval", SAMPLE, "--get", "Sheet1!Z99");
    assertEquals(0, r.exit(), r.err());
    assertEquals("\n", r.out());
  }

  @Test
  void multipleGetsPrintOneLabelledLineEach() {
    Result r = run("eval", SAMPLE, "--get", "Sheet1!A1", "--get", "CoverAmount");
    assertEquals(0, r.exit(), r.err());
    assertEquals("Sheet1!A1=42\nCoverAmount=50000\n", r.out());
  }

  @Test
  void setRecalculatesWhatDependsOnIt() {
    Result r = run("eval", SAMPLE, "--set", "CoverAmount=1000", "--get", "Sheet1!B2");
    assertEquals(0, r.exit(), r.err());
    assertEquals("35\n", r.out());
  }

  @Test
  void setNeedsAValue() {
    assertEquals(2, run("eval", SAMPLE, "--set", "CoverAmount", "--get", "Sheet1!B2").exit());
  }

  @Test
  void verifyReportsFidelityWithCoverage() throws java.io.IOException {
    java.nio.file.Path report = java.nio.file.Files.createTempFile("cellerity", ".html");
    Result r = run("verify", SAMPLE, "--report", report.toString());
    assertTrue(r.exit() <= 1, r.err());
    assertTrue(r.out().contains("fidelity "), r.out());
    assertTrue(r.out().contains("coverage "), r.out());
    assertTrue(java.nio.file.Files.readString(report).startsWith("<!DOCTYPE html>"));
    java.nio.file.Files.delete(report);
  }

  @Test
  void coverageCountsFunctionCalls() {
    Result r = run("coverage", SAMPLE);
    assertTrue(r.exit() <= 1, r.err());
    assertTrue(r.out().contains("function coverage "), r.out());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Nope!A1", "Sheet1!NOTACELL", "UnknownName", "Sheet1!A1:B2"})
  void unresolvableReferencesAreErrors(String ref) {
    Result r = run("eval", SAMPLE, "--get", ref);
    assertEquals(2, r.exit());
    assertTrue(r.err().contains(ref), r.err());
  }

  @Test
  void missingFileIsAnError() {
    Result r = run("eval", "does-not-exist.xlsx", "--get", "A!A1");
    assertEquals(2, r.exit());
    assertTrue(r.err().contains("does-not-exist.xlsx"), r.err());
  }

  @Test
  void evalRequiresAGet() {
    assertEquals(2, run("eval", SAMPLE).exit());
  }

  @Test
  void noArgumentsPrintsUsageAndFails() {
    Result r = run();
    assertEquals(2, r.exit());
    assertTrue(r.err().contains("usage"), r.err());
  }

  @Test
  void helpSucceeds() {
    Result r = run("--help");
    assertEquals(0, r.exit());
    assertTrue(r.out().contains("usage"), r.out());
  }

  @Test
  void unknownCommandFails() {
    assertEquals(2, run("frobnicate").exit());
  }

  @ParameterizedTest
  @CsvSource({"bench,M4", "api,M5"})
  void plannedCommandsSayWhenTheyArrive(String command, String milestone) {
    Result r = run(command, SAMPLE);
    assertEquals(2, r.exit());
    assertTrue(r.err().contains(milestone), r.err());
  }

  @Test
  void inspectListsSheetsNamesCellsFormulasAndCachedValues() {
    Result r = run("inspect", SAMPLE);
    assertEquals(0, r.exit(), r.err());
    String out = r.out();
    assertTrue(out.contains("Date system: 1900"), out);
    assertTrue(out.contains("Writer: Cellerity MakeSample (synthetic)"), out);
    assertTrue(out.contains("Sheet: Sheet1"), out);
    assertTrue(out.contains("Sheet: Rates & Terms"), out);
    assertTrue(out.contains("CoverAmount = Sheet1!$B$1"), out);
    assertTrue(out.contains("LocalNote = Sheet1!$A$4 (sheet 0)"), out);
    assertTrue(out.contains("B2\t1750\t=B1*'Rates & Terms'!B1"), out);
    assertTrue(out.contains("B3\t#DIV/0!\t=1/0"), out);
    assertTrue(out.contains("A2\tPremium model"), out);
    assertTrue(out.contains("B4\t46329 (date 2026-11-03)"), out);
  }

  @Test
  void corruptFileIsAnErrorWithItsKind() throws Exception {
    java.nio.file.Path bad = java.nio.file.Files.createTempFile("bad", ".xlsx");
    try {
      java.nio.file.Files.writeString(bad, "not a workbook");
      Result r = run("inspect", bad.toString());
      assertEquals(2, r.exit());
      assertTrue(r.err().contains("UNSUPPORTED_FORMAT"), r.err());
    } finally {
      java.nio.file.Files.delete(bad);
    }
  }
}
