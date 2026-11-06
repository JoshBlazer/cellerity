package io.github.joshblazer.cellerity.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.truffle.TruffleEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The interpreter is the oracle, so any difference here is a Truffle bug. */
class TruffleDifferentialTest {
  private static final Path CASES =
      Path.of(
          "../cellerity-interp/src/test/resources/io/github/joshblazer/cellerity/interp/function-cases.tsv");

  private static CellRef ref(String a1) {
    return CellRef.parse(a1).orElseThrow();
  }

  static Stream<String> formulas() throws IOException {
    List<String> formulas = new ArrayList<>();
    for (String line : Files.readAllLines(CASES)) {
      if (!line.isBlank() && !line.startsWith("#!")) {
        formulas.add(line.substring(0, line.indexOf('\t')));
      }
    }
    return formulas.stream();
  }

  private static WorkbookEvaluator truffle(ParsedWorkbook book) {
    return new WorkbookEvaluator(
        book, new TruffleEngine(), WorkbookEvaluator.VolatilePolicy.USE_CACHED);
  }

  private static void assertSame(Map<String, Object> cells, String... targets) {
    ParsedWorkbook book = ParsedWorkbook.of(TestBooks.workbook(cells));
    WorkbookEvaluator interpreter = new WorkbookEvaluator(book);
    WorkbookEvaluator truffle = truffle(book);
    for (String target : targets) {
      assertEquals(interpreter.outcome(0, ref(target)), truffle.outcome(0, ref(target)), target);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formulas")
  void everyDocumentedCaseAgrees(String formula) {
    Map<String, Object> cells = TestBooks.fixture();
    cells.put("Z100", "=" + formula);
    assertSame(cells, "Z100");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formulas")
  void everyDocumentedCaseAgreesAsAnArrayFormula(String formula) {
    var fixture = TestBooks.fixture();
    Map<CellRef, io.github.joshblazer.cellerity.io.Cell> cells = new LinkedHashMap<>();
    TestBooks.sheet("Sheet1", fixture).cells().forEach(cells::put);
    CellRef at = ref("Z100");
    cells.put(
        at,
        new io.github.joshblazer.cellerity.io.Cell(
            at,
            java.util.Optional.empty(),
            java.util.Optional.of(
                new io.github.joshblazer.cellerity.io.Formula(
                    io.github.joshblazer.cellerity.io.Formula.Kind.ARRAY,
                    java.util.Optional.of(formula),
                    java.util.OptionalInt.empty(),
                    java.util.Optional.of("Z100:Z101"))),
            false));
    var sheet =
        new io.github.joshblazer.cellerity.io.Sheet(
            "Sheet1",
            io.github.joshblazer.cellerity.io.SheetKind.WORKSHEET,
            io.github.joshblazer.cellerity.io.SheetState.VISIBLE,
            cells);
    ParsedWorkbook book = ParsedWorkbook.of(TestBooks.workbook(List.of(sheet), List.of()));
    WorkbookEvaluator interpreter = new WorkbookEvaluator(book);
    WorkbookEvaluator truffle = truffle(book);
    for (String target : List.of("Z100", "Z101")) {
      assertEquals(interpreter.outcome(0, ref(target)), truffle.outcome(0, ref(target)), target);
    }
  }

  @Test
  void aNodeThatStopsSeeingNumbersStillAgrees() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 2.0);
    cells.put("B1", "=A1*3");
    ParsedWorkbook book = ParsedWorkbook.of(TestBooks.workbook(cells));
    WorkbookEvaluator interpreter = new WorkbookEvaluator(book);
    WorkbookEvaluator truffle = truffle(book);
    List<Value> inputs = new ArrayList<>();
    for (int i = 0; i < 2_000; i++) {
      inputs.add(new Value.Num(i));
    }
    inputs.add(new Value.Text("7"));
    inputs.add(new Value.Text("not a number"));
    inputs.add(new Value.Bool(true));
    inputs.add(Value.BLANK);
    inputs.add(new Value.Err(io.github.joshblazer.cellerity.core.ExcelError.NA));
    inputs.add(new Value.Num(1e308));
    inputs.add(new Value.Num(5));
    for (Value input : inputs) {
      interpreter.set(0, ref("A1"), input);
      truffle.set(0, ref("A1"), input);
      assertEquals(interpreter.value(0, ref("B1")), truffle.value(0, ref("B1")), input.toString());
    }
  }

  @Test
  void oneFormulaTextAtManyPositionsAgrees() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("A2", 2.0);
    cells.put("B1", "=ROW()+COLUMN()+A1");
    cells.put("C2", "=ROW()+COLUMN()+A1");
    cells.put("D3", "=A1:A2*2");
    cells.put("D1", "=A1:A2*2");
    cells.put("E2", "=A1:A2*2");
    assertSame(cells, "B1", "C2", "D1", "D3", "E2");
  }

  /** Each column has one shape down its rows, so nodes built at row 1 run everywhere. */
  private static Map<String, Object> sameShapesInManyRows() {
    Map<String, Object> cells = new LinkedHashMap<>();
    for (int i = 1; i <= 40; i++) {
      cells.put("A" + i, (double) i);
    }
    for (int i = 1; i <= 40; i++) {
      cells.put("B" + i, "=A" + i + "*$A$2+A$3");
      cells.put("C" + i, "=SUM($A$1:A" + i + ")");
      cells.put("D" + i, "=SUM(A:A)+ROW()+SUM(" + i + ":" + i + ")");
      cells.put("E" + i, "=IF(A" + i + ">20,B" + i + ",C" + i + ")");
      cells.put("F" + i, "=SUM(IF({1,0}>0,A" + i + ",B" + i + "))");
      cells.put("G" + i, "=SUM(A" + i + ":$A$40 A$1:A" + i + ")");
      cells.put("H" + i, "=IFERROR(A" + i + "/0,B" + i + ")");
      cells.put("I" + i, "=RELATIVE+A" + i);
      cells.put("J" + i, "=-A" + i + "%&\"x\"");
    }
    return cells;
  }

  private static List<io.github.joshblazer.cellerity.io.DefinedName> relativeName() {
    return List.of(
        new io.github.joshblazer.cellerity.io.DefinedName(
            "RELATIVE", "A1", java.util.OptionalInt.empty(), false));
  }

  @Test
  void aShapeAgreesAtEveryCellItRunsAt() {
    Map<String, Object> cells = sameShapesInManyRows();
    ParsedWorkbook book =
        ParsedWorkbook.of(
            TestBooks.workbook(List.of(TestBooks.sheet("Sheet1", cells)), relativeName()));
    TruffleEngine engine = new TruffleEngine();
    WorkbookEvaluator interpreter = new WorkbookEvaluator(book);
    WorkbookEvaluator truffle =
        new WorkbookEvaluator(book, engine, WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    for (String cell : cells.keySet()) {
      assertEquals(interpreter.outcome(0, ref(cell)), truffle.outcome(0, ref(cell)), cell);
    }
    // Nine columns of one shape, except the ranges that depend on the row in two directions.
    assertTrue(engine.shapes() <= 12, "shapes: " + engine.shapes());
  }

  @Test
  void aShapeAgreesOnEverySheetItRunsOn() {
    Map<String, Object> one = new LinkedHashMap<>();
    one.put("A1", 1.0);
    one.put("B1", "=A1*2");
    Map<String, Object> two = new LinkedHashMap<>();
    two.put("A1", 5.0);
    two.put("B1", "=A1*2");
    ParsedWorkbook book =
        ParsedWorkbook.of(
            TestBooks.workbook(
                List.of(TestBooks.sheet("One", one), TestBooks.sheet("Two", two)), List.of()));
    TruffleEngine engine = new TruffleEngine();
    WorkbookEvaluator truffle =
        new WorkbookEvaluator(book, engine, WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    assertEquals(new Value.Num(2), truffle.value(0, ref("B1")));
    assertEquals(new Value.Num(10), truffle.value(1, ref("B1")));
    assertEquals(1, engine.shapes());
  }

  @Test
  void formulasThatLookAlikeButReadDifferentCellsDoNotShare() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("B1", "=A1*2"); // one column to the left
    cells.put("C1", "=A1*2"); // two columns to the left
    cells.put("D1", "=$A1*2");
    cells.put("E1", "=$A1*2"); // the same shape as D1, since the column is fixed
    cells.put("A2", 7.0);
    cells.put("B2", "=A2*2"); // the same shape as B1
    ParsedWorkbook book = ParsedWorkbook.of(TestBooks.workbook(cells));
    TruffleEngine engine = new TruffleEngine();
    WorkbookEvaluator truffle =
        new WorkbookEvaluator(book, engine, WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    for (String cell : List.of("B1", "C1", "D1", "E1", "B2")) {
      truffle.value(0, ref(cell));
    }
    assertEquals(new Value.Num(14), truffle.value(0, ref("B2")));
    assertEquals(3, engine.shapes());
  }

  @Test
  void onlyAFinalAddOrSubtractSnapsToZero() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", "=0.5-0.4-0.1");
    cells.put("A2", "=(0.5-0.4-0.1)");
    cells.put("A3", "=1*(0.5-0.4-0.1)");
    assertSame(cells, "A1", "A2", "A3");
  }

  @Test
  void laziessAndErrorsAgree() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", "=IF(TRUE,1,A1)");
    cells.put("A2", "=IF(1/0,1,2)");
    cells.put("A3", "=IF(FALSE,1)");
    cells.put("A4", "=IF(TRUE,,2)");
    cells.put("A5", "=IF(TRUE)");
    cells.put("A6", "=IF(\"x\",1,2)");
    cells.put("A7", "=SUM(IF({1,0},1,2))");
    cells.put("A8", "=NOSUCHFUNCTION(1)");
    cells.put("A9", "=[1]Sheet!A1");
    assertSame(cells, "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9");
  }

  @Test
  void aLongChainAgreesWithoutOverflowing() {
    Map<String, Object> cells = new java.util.HashMap<>();
    cells.put("A1", 1.0);
    for (int i = 2; i <= 20_000; i++) {
      cells.put("A" + i, "=A" + (i - 1) + "+1");
    }
    assertSame(cells, "A20000");
  }

  @Test
  void cyclesAgree() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", "=B1+1");
    cells.put("B1", "=A1+1");
    cells.put("C1", "=A1+1");
    assertSame(cells, "A1", "B1", "C1");
  }

  @Test
  void incrementalRecalculationWorksThroughTruffle() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("B1", "=A1+1");
    cells.put("C1", "=B1*2");
    cells.put("D1", "=5*5");
    IncrementalWorkbook model =
        new IncrementalWorkbook(
            ParsedWorkbook.of(TestBooks.workbook(cells)),
            new TruffleEngine(),
            WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    model.recalculate();
    long before = model.evaluations();
    model.set(0, ref("A1"), new Value.Num(10));
    model.recalculate();
    assertEquals(2, model.evaluations() - before);
    assertEquals(new Value.Num(22), model.value(0, ref("C1")));
  }

  @Test
  void theEngineReportsWhatItDelegates() {
    TruffleEngine engine = new TruffleEngine();
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("B1", "=A1+1");
    cells.put("B2", "={1,2}");
    WorkbookEvaluator evaluator =
        new WorkbookEvaluator(
            ParsedWorkbook.of(TestBooks.workbook(cells)),
            engine,
            WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    evaluator.value(0, ref("B1"));
    long plain = engine.nodes();
    assertTrue(plain > 0);
    assertEquals(0, engine.delegatedNodes());
    evaluator.value(0, ref("B2"));
    assertEquals(1, engine.delegatedNodes());
    assertFalse(TruffleEngine.runtimeName().isEmpty());
  }
}
