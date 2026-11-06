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
