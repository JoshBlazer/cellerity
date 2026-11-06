package io.github.joshblazer.cellerity.truffle;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.interp.WorkbookEvaluator;
import io.github.joshblazer.cellerity.io.Cell;
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

/**
 * Shows which Truffle runtime is in use and whether formulas compile. It is evidence for ADR-0006,
 * not a benchmark. Run it with {@code -Dpolyglot.engine.TraceCompilation=true} to see compilations.
 */
public final class TruffleProbe {
  private static final int RECALCULATIONS = 200_000;

  private TruffleProbe() {}

  public static void main(String[] args) {
    CellRef input = CellRef.parse("A1").orElseThrow();
    CellRef output = CellRef.parse("B1").orElseThrow();
    Map<CellRef, Cell> cells = new LinkedHashMap<>();
    cells.put(input, new Cell(input, Optional.of(new Value.Num(1)), Optional.empty(), false));
    cells.put(
        output,
        new Cell(
            output,
            Optional.empty(),
            Optional.of(
                new Formula(
                    Formula.Kind.NORMAL,
                    Optional.of("IF(A1>0,(A1*2+1)/3-A1^2,A1-1)*1.5"),
                    OptionalInt.empty(),
                    Optional.empty())),
            false));
    ParsedWorkbook book =
        ParsedWorkbook.of(
            new Workbook(
                List.of(new Sheet("S", SheetKind.WORKSHEET, SheetState.VISIBLE, cells)),
                List.of(),
                DateSystem.SYSTEM_1900,
                new WorkbookInfo(Optional.empty(), Optional.empty(), Optional.empty(), false)));
    WorkbookEvaluator evaluator =
        new WorkbookEvaluator(
            book, new TruffleEngine(), WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    double sum = 0;
    for (int i = 0; i < RECALCULATIONS; i++) {
      evaluator.set(0, input, new Value.Num(i % 100 + 1.0));
      sum += ((Value.Num) evaluator.value(0, output)).value();
    }
    System.out.println("truffle runtime: " + TruffleEngine.runtimeName());
    System.out.println(
        "java: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
    System.out.println("recalculations: " + RECALCULATIONS + ", checksum: " + sum);
  }
}
