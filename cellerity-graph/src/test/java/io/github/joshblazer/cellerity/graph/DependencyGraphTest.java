package io.github.joshblazer.cellerity.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.FormulaParser;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.SheetKind;
import io.github.joshblazer.cellerity.io.SheetState;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.WorkbookInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DependencyGraphTest {
  private static CellKey key(String a1) {
    return new CellKey(0, CellRef.parse(a1).orElseThrow());
  }

  private static CellKey key2(String a1) {
    return new CellKey(1, CellRef.parse(a1).orElseThrow());
  }

  private static Sheet sheet(String name, Map<String, String> formulas) {
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    formulas.forEach((a1, text) -> {
      CellRef ref = CellRef.parse(a1).orElseThrow();
      cells.put(
          ref,
          text.startsWith("=")
              ? new Cell(
                  ref,
                  Optional.empty(),
                  Optional.of(
                      new Formula(
                          Formula.Kind.NORMAL,
                          Optional.of(text.substring(1)),
                          OptionalInt.empty(),
                          Optional.empty())),
                  false)
              : new Cell(
                  ref,
                  Optional.of(new Value.Num(Double.parseDouble(text))),
                  Optional.empty(),
                  false));
    });
    return new Sheet(name, SheetKind.WORKSHEET, SheetState.VISIBLE, cells);
  }

  private static ParsedWorkbook book(Sheet... sheets) {
    return ParsedWorkbook.of(
        new Workbook(
            List.of(sheets),
            List.of(),
            DateSystem.SYSTEM_1900,
            new WorkbookInfo(Optional.empty(), Optional.empty(), Optional.empty(), false)));
  }

  private static DependencyGraph graph(Map<String, String> formulas) {
    return DependencyGraph.build(book(sheet("S", formulas)), (name, from) -> Optional.empty());
  }

  @Test
  void aFormulaDependsOnCellsAndRanges() {
    DependencyGraph g =
        graph(
            Map.of(
                "A1",
                "1",
                "A2",
                "2",
                "B1",
                "=A1",
                "B2",
                "=SUM(A1:A2)",
                "B3",
                "=SUM(A:A)",
                "B4",
                "=SUM(C1:C2)"));
    assertEquals(Set.of(key("B1"), key("B2"), key("B3")), Set.copyOf(g.dependents(key("A1"))));
    assertEquals(Set.of(key("B2"), key("B3")), Set.copyOf(g.dependents(key("A2"))));
    assertEquals(List.of(key("B3")), g.dependents(key("A99")));
    assertEquals(List.of(), g.dependents(key("Z1")));
  }

  @Test
  void referencesFollowTheirSheet() {
    DependencyGraph g =
        DependencyGraph.build(
            book(
                sheet("S", Map.of("A1", "1", "B1", "=T!A1")),
                sheet("T", Map.of("A1", "1", "B1", "=A1"))),
            (name, from) -> Optional.empty());
    assertEquals(
        List.of(key2("B1")),
        g.dependents(key2("A1")).stream().filter(k -> k.sheet() == 1).toList());
    assertTrue(g.dependents(key2("A1")).contains(key("B1")));
    assertTrue(g.dependents(key("A1")).isEmpty());
  }

  @Test
  void namesAreFollowedAndALoopOfNamesEnds() {
    Map<String, Expr> names =
        Map.of(
            "RATE", FormulaParser.parse("$A$1").expr(),
            "DOUBLE", FormulaParser.parse("RATE*2").expr(),
            "LOOP", FormulaParser.parse("LOOP+1").expr());
    DependencyGraph g =
        DependencyGraph.build(
            book(sheet("S", Map.of("A1", "1", "B1", "=DOUBLE", "B2", "=LOOP"))),
            (name, from) ->
                Optional.ofNullable(names.get(name.toUpperCase(java.util.Locale.ROOT))));
    assertEquals(List.of(key("B1")), g.dependents(key("A1")));
  }

  @Test
  void aRelativeNameReadsRelativeToTheFormulaUsingIt() {
    Expr next = FormulaParser.parse("A2").expr();
    DependencyGraph g =
        DependencyGraph.build(
            book(sheet("S", Map.of("C1", "=NEXT", "C2", "1", "A2", "1"))),
            (name, from) -> Optional.of(next));
    assertEquals(List.of(key("C1")), g.dependents(key("C2")));
    assertEquals(List.of(), g.dependents(key("A2")));
  }

  @Test
  void aChangeReachesEverythingDownstreamAndNothingElse() {
    DependencyGraph g =
        graph(Map.of("A1", "1", "B1", "=A1", "C1", "=B1", "D1", "=C1+B1", "E1", "=Z1"));
    assertEquals(Set.of(key("B1"), key("C1"), key("D1")), g.affectedBy(List.of(key("A1"))));
    assertEquals(Set.of(), g.affectedBy(List.of(key("Y1"))));
  }

  @Test
  void changingAFormulaIncludesIt() {
    DependencyGraph g = graph(Map.of("A1", "1", "B1", "=A1", "C1", "=B1"));
    assertEquals(Set.of(key("B1"), key("C1")), g.affectedBy(List.of(key("B1"))));
  }

  @Test
  void anArrayMembersReadersDependOnItsMaster() {
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    CellRef b1 = CellRef.parse("B1").orElseThrow();
    cells.put(
        CellRef.parse("A1").orElseThrow(),
        new Cell(
            CellRef.parse("A1").orElseThrow(),
            Optional.of(new Value.Num(1)),
            Optional.empty(),
            false));
    cells.put(
        b1,
        new Cell(
            b1,
            Optional.empty(),
            Optional.of(
                new Formula(
                    Formula.Kind.ARRAY,
                    Optional.of("A1*{1;2}"),
                    OptionalInt.empty(),
                    Optional.of("B1:B2"))),
            false));
    CellRef c1 = CellRef.parse("C1").orElseThrow();
    cells.put(
        c1,
        new Cell(
            c1,
            Optional.empty(),
            Optional.of(
                new Formula(
                    Formula.Kind.NORMAL,
                    Optional.of("B2+1"),
                    OptionalInt.empty(),
                    Optional.empty())),
            false));
    DependencyGraph g =
        DependencyGraph.build(
            book(new Sheet("S", SheetKind.WORKSHEET, SheetState.VISIBLE, cells)),
            (name, from) -> Optional.empty());
    assertTrue(g.affectedBy(List.of(key("A1"))).containsAll(Set.of(key("B1"), key("C1"))));
    assertFalse(g.formulas().contains(key("B2")), "members are not formulas of their own");
    List<CellKey> order = g.evaluationOrder(g.affectedBy(List.of(key("A1"))));
    assertTrue(order.indexOf(key("B1")) < order.indexOf(key("C1")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "=NOW()",
        "=TODAY()",
        "=RAND()",
        "=RANDBETWEEN(1,2)",
        "=OFFSET(A1,1,1)",
        "=INDIRECT(\"A1\")",
        "=1+IF(A1,OFFSET(A1,1,1),2)",
        "=SUM(A1:INDEX(C1:C5,2))",
        "=SUMIF(A1:A5,1,B1)",
        "=AVERAGEIF(A1:A5,1,B1:B2)",
      })
  void formulasWithReadsThatDependOnValuesAreAlwaysDirty(String formula) {
    DependencyGraph g = graph(Map.of("A1", "1", "F1", formula, "G1", "=F1"));
    assertEquals(Set.of(key("F1")), g.alwaysDirty());
    assertTrue(g.affectedBy(List.of()).containsAll(Set.of(key("F1"), key("G1"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"=SUMIF(A1:A5,1,B1:B5)", "=SUMIF(A1:A5,1)", "=INDEX(A1:A5,2)", "=A1:A2"})
  void ordinaryFormulasAreNot(String formula) {
    assertEquals(Set.of(), graph(Map.of("A1", "1", "F1", formula)).alwaysDirty());
  }

  @Test
  void anAlwaysDirtyFormulaInsideANameCounts() {
    Expr offset = FormulaParser.parse("OFFSET($A$1,1,1)").expr();
    DependencyGraph g =
        DependencyGraph.build(
            book(sheet("S", Map.of("A1", "1", "B1", "=DYNAMIC"))),
            (name, from) -> Optional.of(offset));
    assertEquals(Set.of(key("B1")), g.alwaysDirty());
  }

  @Test
  void orderPutsPrecedentsFirst() {
    DependencyGraph g =
        graph(Map.of("A1", "1", "E1", "=D1", "D1", "=C1+B1", "C1", "=B1", "B1", "=A1"));
    assertEquals(
        List.of(key("B1"), key("C1"), key("D1"), key("E1")),
        g.evaluationOrder(g.affectedBy(List.of(key("A1")))));
  }

  @Test
  void cyclesIncludeSelfReferencesAndAreStable() {
    DependencyGraph g =
        graph(
            Map.of(
                "A1", "=B1", "B1", "=C1", "C1", "=A1", "D1", "=D1", "E1", "=A1", "F1", "1", "G1",
                "=F1"));
    assertEquals(List.of(List.of(key("A1"), key("B1"), key("C1")), List.of(key("D1"))), g.cycles());
    List<CellKey> order = g.evaluationOrder(g.formulas());
    assertEquals(g.formulas().size(), order.size(), "cycle cells are still ordered");
    assertTrue(
        order.indexOf(key("A1")) < order.indexOf(key("E1")), "downstream of a cycle is last");
  }

  @Test
  void aLongChainDoesNotOverflow() {
    Map<String, String> cells = new LinkedHashMap<>();
    cells.put("A1", "1");
    List<CellKey> expected = new ArrayList<>();
    for (int i = 2; i <= 200_000; i++) {
      cells.put("A" + i, "=A" + (i - 1));
      expected.add(key("A" + i));
    }
    DependencyGraph g = graph(cells);
    assertEquals(List.of(), g.cycles());
    assertEquals(expected, g.evaluationOrder(g.affectedBy(List.of(key("A1")))));
  }
}
