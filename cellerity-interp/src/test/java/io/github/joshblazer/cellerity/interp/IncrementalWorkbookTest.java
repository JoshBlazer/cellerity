package io.github.joshblazer.cellerity.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.graph.CellKey;
import io.github.joshblazer.cellerity.interp.IncrementalWorkbook.Recalculation;
import io.github.joshblazer.cellerity.interp.WorkbookEvaluator.VolatilePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class IncrementalWorkbookTest {
  private static CellRef ref(String a1) {
    return CellRef.parse(a1).orElseThrow();
  }

  private static IncrementalWorkbook model(Map<String, Object> cells, VolatilePolicy policy) {
    IncrementalWorkbook model =
        new IncrementalWorkbook(
            ParsedWorkbook.of(TestBooks.workbook(cells)), new Interpreter(), policy);
    model.recalculate();
    return model;
  }

  private static IncrementalWorkbook model(Map<String, Object> cells) {
    return model(cells, VolatilePolicy.USE_CACHED);
  }

  /** Two separate chains of three formulas, and 100 unrelated formulas. */
  private static Map<String, Object> twoChains() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("A2", "=A1+1");
    cells.put("A3", "=A2+1");
    cells.put("A4", "=A3+1");
    cells.put("B1", 10.0);
    cells.put("B2", "=B1+1");
    cells.put("B3", "=B2+1");
    cells.put("B4", "=B3+1");
    for (int i = 1; i <= 100; i++) {
      cells.put("D" + i, "=C1+" + i);
    }
    cells.put("C1", 5.0);
    return cells;
  }

  @Test
  void changingAnInputRecalculatesOnlyWhatItAffects() {
    IncrementalWorkbook model = model(twoChains());
    long before = model.evaluations();

    model.set(0, ref("A1"), new Value.Num(100));
    Recalculation recalculation = model.recalculate();

    assertEquals(3, model.evaluations() - before);
    assertEquals(3, recalculation.evaluated());
    assertEquals(new Value.Num(103), model.value(0, ref("A4")));
    assertEquals(new Value.Num(13), model.value(0, ref("B4")));
  }

  @Test
  void nothingChangedMeansNothingEvaluated() {
    IncrementalWorkbook model = model(twoChains());
    long before = model.evaluations();
    model.recalculate();
    assertEquals(before, model.evaluations());
  }

  @Test
  void readingAValueEvaluatesNothingNew() {
    IncrementalWorkbook model = model(twoChains());
    long before = model.evaluations();
    assertEquals(new Value.Num(4), model.value(0, ref("A4")));
    assertEquals(before, model.evaluations());
  }

  @Test
  void aChangeInsideARangeReachesItsReaders() {
    Map<String, Object> cells = new LinkedHashMap<>();
    for (int i = 1; i <= 5; i++) {
      cells.put("A" + i, (double) i);
    }
    cells.put("B1", "=SUM(A1:A5)");
    cells.put("B2", "=SUM(A:A)");
    cells.put("B3", "=SUM(C1:C5)");
    cells.put("C1", 1.0);
    IncrementalWorkbook model = model(cells);
    long before = model.evaluations();

    model.set(0, ref("A3"), new Value.Num(30));
    model.recalculate();

    assertEquals(2, model.evaluations() - before);
    assertEquals(new Value.Num(42), model.value(0, ref("B1")));
    assertEquals(new Value.Num(42), model.value(0, ref("B2")));
  }

  @Test
  void aChangeToANewCellInsideARangeReachesItsReaders() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("B1", "=SUM(A:A)");
    IncrementalWorkbook model = model(cells);
    model.set(0, ref("A9"), new Value.Num(2));
    model.recalculate();
    assertEquals(new Value.Num(3), model.value(0, ref("B1")));
  }

  @Test
  void offsetAndIndirectAreRecalculatedOnEveryChange() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("A2", 2.0);
    cells.put("B1", "=OFFSET(A1,1,0)");
    cells.put("B2", "=INDIRECT(\"A2\")");
    cells.put("B3", "=B1+B2");
    cells.put("B4", "=A1+1");
    IncrementalWorkbook model = model(cells);
    long before = model.evaluations();

    model.set(0, ref("A2"), new Value.Num(20));
    model.recalculate();

    assertEquals(3, model.evaluations() - before, "OFFSET, INDIRECT, and their reader, not B4");
    assertEquals(new Value.Num(40), model.value(0, ref("B3")));
  }

  @Test
  void aSumifThatReadsBeyondItsWrittenRangeIsNotMissed() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("A2", 1.0);
    cells.put("B1", 10.0);
    cells.put("B2", 20.0);
    cells.put("C1", "=SUMIF(A1:A2,1,B1)");
    IncrementalWorkbook model = model(cells);
    assertEquals(new Value.Num(30), model.value(0, ref("C1")));
    model.set(0, ref("B2"), new Value.Num(200));
    model.recalculate();
    assertEquals(new Value.Num(210), model.value(0, ref("C1")));
  }

  @Test
  void aChangeReachesAFormulaThroughARelativeName() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("C1", "=NEXT+1");
    cells.put("C2", 1.0);
    var name =
        new io.github.joshblazer.cellerity.io.DefinedName(
            "NEXT", "A2", java.util.OptionalInt.empty(), false);
    IncrementalWorkbook model =
        new IncrementalWorkbook(
            ParsedWorkbook.of(
                TestBooks.workbook(List.of(TestBooks.sheet("Sheet1", cells)), List.of(name))),
            new Interpreter(),
            VolatilePolicy.USE_CACHED);
    model.recalculate();
    assertEquals(new Value.Num(2), model.value(0, ref("C1")));
    model.set(0, ref("C2"), new Value.Num(10));
    model.recalculate();
    assertEquals(new Value.Num(11), model.value(0, ref("C1")));
  }

  @Test
  void volatileFunctionsRecalculateWhenCalculatingThem() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", 1.0);
    cells.put("B1", "=NOW()");
    cells.put("B2", "=A1+1");
    IncrementalWorkbook model = model(cells, VolatilePolicy.CALCULATE);
    long before = model.evaluations();
    model.recalculate();
    assertEquals(1, model.evaluations() - before);
  }

  @Test
  void cyclesAreFoundAndReported() {
    Map<String, Object> cells = new LinkedHashMap<>();
    cells.put("A1", "=B1+1");
    cells.put("B1", "=A1+1");
    cells.put("C1", "=C1");
    cells.put("D1", 1.0);
    cells.put("D2", "=D1+1");
    IncrementalWorkbook model = model(cells);

    assertEquals(
        List.of(
            List.of(new CellKey(0, ref("A1")), new CellKey(0, ref("B1"))),
            List.of(new CellKey(0, ref("C1")))),
        model.cycles());
    assertEquals("cycle", model.outcome(0, ref("C1")).detail());
    assertEquals(new Value.Num(2), model.value(0, ref("D2")));
  }

  @Test
  void aLongChainRecalculatesWithoutOverflowing() {
    Map<String, Object> cells = new java.util.HashMap<>();
    cells.put("A1", 1.0);
    for (int i = 2; i <= 50_000; i++) {
      cells.put("A" + i, "=A" + (i - 1) + "+1");
    }
    IncrementalWorkbook model = model(cells);
    long before = model.evaluations();
    model.set(0, ref("A49999"), new Value.Num(0));
    model.recalculate();
    assertEquals(1, model.evaluations() - before);
    assertEquals(new Value.Num(1), model.value(0, ref("A50000")));
  }

  @Test
  void overridingAFormulaStopsItCalculating() {
    IncrementalWorkbook model = model(twoChains());
    model.set(0, ref("A2"), new Value.Num(50));
    model.recalculate();
    assertEquals(new Value.Num(52), model.value(0, ref("A4")));
    long before = model.evaluations();
    model.set(0, ref("A1"), new Value.Num(7));
    model.recalculate();
    assertEquals(new Value.Num(52), model.value(0, ref("A4")));
    assertTrue(model.evaluations() - before <= 2);
  }

  /** Builds an acyclic sheet: each formula in column B reads only earlier cells. */
  private static Map<String, Object> randomSheet(List<Integer> seeds) {
    Map<String, Object> cells = new LinkedHashMap<>();
    for (int i = 1; i <= 8; i++) {
      cells.put("A" + i, (double) i);
    }
    for (int i = 0; i < seeds.size(); i++) {
      int s = Math.abs(seeds.get(i));
      int row = i + 1;
      String a = "A" + (s % 8 + 1);
      String earlier = row == 1 ? a : "B" + ((s >> 3) % (row - 1) + 1);
      String formula =
          switch (s % 6) {
            case 0 -> "=" + a + "+" + earlier;
            case 1 -> "=SUM(A1:" + a + ")+" + earlier;
            case 2 -> "=IF(" + a + ">4," + earlier + ",1)";
            case 3 -> "=OFFSET(A1," + (s % 8) + ",0)*2";
            case 4 -> "=MAX(" + earlier + "," + a + ")";
            default -> "=SUMIF(A1:A8,\">3\",A1)+" + earlier;
          };
      cells.put("B" + row, formula);
    }
    return cells;
  }

  @Property(tries = 200)
  void incrementalAlwaysAgreesWithAFullRecalculation(
      @ForAll @Size(min = 1, max = 30) List<@IntRange(min = 0, max = 100_000) Integer> seeds,
      @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 1, max = 8) Integer> changes) {
    Map<String, Object> cells = randomSheet(seeds);
    ParsedWorkbook parsed = ParsedWorkbook.of(TestBooks.workbook(cells));
    IncrementalWorkbook incremental =
        new IncrementalWorkbook(parsed, new Interpreter(), VolatilePolicy.USE_CACHED);
    incremental.recalculate();
    List<CellRef> changed = new ArrayList<>();
    WorkbookEvaluator full =
        new WorkbookEvaluator(parsed, new Interpreter(), VolatilePolicy.USE_CACHED);
    for (int row : changes) {
      CellRef at = ref("A" + row);
      Value value = new Value.Num(row * 7.0 + changed.size());
      changed.add(at);
      incremental.set(0, at, value);
      full.set(0, at, value);
      incremental.recalculate();
      for (int i = 1; i <= seeds.size(); i++) {
        CellRef cell = ref("B" + i);
        assertEquals(
            full.value(0, cell), incremental.value(0, cell), cells.get("B" + i).toString());
      }
    }
  }
}
