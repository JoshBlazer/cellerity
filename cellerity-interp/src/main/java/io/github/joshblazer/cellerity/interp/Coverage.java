package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.functions.Functions;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.Exprs;
import io.github.joshblazer.cellerity.formula.ParsedFormula;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.formula.Span;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counts function calls, supported or not. Coverage is the share of calls, not of functions. */
public final class Coverage {
  private final Map<String, Long> calls = new HashMap<>();

  /** Adds a workbook's calls. Each cell's formula counts once per call in it. */
  public void add(ParsedWorkbook workbook) {
    for (int s = 0; s < workbook.workbook().sheets().size(); s++) {
      for (ParsedFormula formula : workbook.formulas(s).values()) {
        if (formula.ok() && formula.kind() != ParsedFormula.Kind.DATA_TABLE) {
          for (String name : Exprs.functionNames(formula.expr())) {
            calls.merge(new Expr.Call(name, List.of(), Span.NONE).canonicalName(), 1L, Long::sum);
          }
        }
      }
    }
  }

  public long totalCalls() {
    return calls.values().stream().mapToLong(Long::longValue).sum();
  }

  public long supportedCalls() {
    return calls.entrySet().stream()
        .filter(e -> Functions.supported(e.getKey()))
        .mapToLong(Map.Entry::getValue)
        .sum();
  }

  /** The share of calls to functions we implement, or 1 if there are no calls. */
  public double rate() {
    return totalCalls() == 0 ? 1 : (double) supportedCalls() / totalCalls();
  }

  /** Unsupported functions, most called first. */
  public Map<String, Long> missing() {
    Map<String, Long> missing = new LinkedHashMap<>();
    calls.entrySet().stream()
        .filter(e -> !Functions.supported(e.getKey()))
        .sorted(
            Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .forEach(e -> missing.put(e.getKey(), e.getValue()));
    return missing;
  }
}
