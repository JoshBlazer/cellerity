package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.graph.CellKey;
import io.github.joshblazer.cellerity.graph.DependencyGraph;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recalculates only what a change can affect. See {@code docs/design/graph.md}. */
public final class IncrementalWorkbook {
  /**
   * What one recalculation did.
   *
   * @param dirty how many cells were recalculated or forgotten
   * @param evaluated how many formulas were evaluated
   */
  public record Recalculation(int dirty, long evaluated) {}

  private final WorkbookEvaluator evaluator;
  private final DependencyGraph graph;
  private final Map<CellKey, Value> pending = new LinkedHashMap<>();
  private boolean calculated;

  public IncrementalWorkbook(
      ParsedWorkbook workbook, FormulaEngine engine, WorkbookEvaluator.VolatilePolicy policy) {
    evaluator = new WorkbookEvaluator(workbook, engine, policy);
    graph = DependencyGraph.build(workbook, evaluator::name);
  }

  /** Changes an input. It takes effect at the next {@link #recalculate}. */
  public void set(int sheet, CellRef at, Value value) {
    pending.put(new CellKey(sheet, at), value);
  }

  /** Brings every cell up to date, evaluating only what the changes affect. */
  public Recalculation recalculate() {
    long before = evaluator.evaluations();
    Set<CellKey> dirty =
        calculated ? graph.affectedBy(pending.keySet()) : new HashSet<>(graph.formulas());
    calculated = true;
    Set<CellKey> forgotten = new HashSet<>(dirty);
    forgotten.addAll(pending.keySet());
    pending.forEach((key, value) -> evaluator.override(key.sheet(), key.at(), value));
    forgotten.forEach(key -> evaluator.forget(key.sheet(), key.at()));
    pending.clear();
    evaluator.startRecalculation();
    for (CellKey key : graph.evaluationOrder(dirty)) {
      evaluator.outcome(key.sheet(), key.at());
    }
    return new Recalculation(forgotten.size(), evaluator.evaluations() - before);
  }

  public Value value(int sheet, CellRef at) {
    return evaluator.value(sheet, at);
  }

  public WorkbookEvaluator.Outcome outcome(int sheet, CellRef at) {
    return evaluator.outcome(sheet, at);
  }

  /** Static cycles. A cycle through an untaken branch never happens when evaluating. */
  public List<List<CellKey>> cycles() {
    return graph.cycles();
  }

  public long evaluations() {
    return evaluator.evaluations();
  }
}
