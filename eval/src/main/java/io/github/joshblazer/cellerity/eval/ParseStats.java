package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.formula.Diagnostic;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.Exprs;
import io.github.joshblazer.cellerity.formula.FormulaParser;
import io.github.joshblazer.cellerity.formula.FormulaPrinter;
import io.github.joshblazer.cellerity.formula.ParseResult;
import io.github.joshblazer.cellerity.formula.ParsedFormula;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Parse results across a corpus. Each cell counts once, so shared formulas count per cell. */
final class ParseStats {
  private static final int MAX_FAILURE_EXAMPLES = 30;

  private long formulaCells;
  private long parsed;
  private long failed;
  private long dataTables;
  private long roundTripFailures;
  private long functionCalls;
  private final Map<String, Long> functions = new HashMap<>();
  private final Map<String, Long> functionFiles = new HashMap<>();
  private final List<String> failureExamples = new ArrayList<>();

  void add(ParsedWorkbook workbook) {
    Set<String> used = new HashSet<>();
    for (int sheet = 0; sheet < workbook.workbook().sheets().size(); sheet++) {
      for (Map.Entry<CellRef, ParsedFormula> entry : workbook.formulas(sheet).entrySet()) {
        add(entry.getValue(), workbook.workbook().sheets().get(sheet).name(), entry.getKey(), used);
      }
    }
    used.forEach(name -> functionFiles.merge(name, 1L, Long::sum));
  }

  private void add(ParsedFormula formula, String sheet, CellRef at, Set<String> used) {
    if (formula.kind() == ParsedFormula.Kind.DATA_TABLE) {
      dataTables++;
      return;
    }
    formulaCells++;
    if (!formula.ok()) {
      failed++;
      if (failureExamples.size() < MAX_FAILURE_EXAMPLES) {
        Diagnostic first = formula.diagnostics().get(0);
        failureExamples.add(sheet + "!" + at.toA1() + ": " + first.message());
      }
      return;
    }
    parsed++;
    for (String name : Exprs.functionNames(formula.expr())) {
      functions.merge(name, 1L, Long::sum);
      used.add(name);
      functionCalls++;
    }
    if (!roundTrips(formula.expr())) {
      roundTripFailures++;
      if (failureExamples.size() < MAX_FAILURE_EXAMPLES) {
        failureExamples.add(sheet + "!" + at.toA1() + ": does not round-trip");
      }
    }
  }

  private static boolean roundTrips(Expr expr) {
    ParseResult again = FormulaParser.parse(FormulaPrinter.print(expr));
    return again.ok() && Exprs.withoutSpans(again.expr()).equals(Exprs.withoutSpans(expr));
  }

  long formulaCells() {
    return formulaCells;
  }

  long parsed() {
    return parsed;
  }

  long failed() {
    return failed;
  }

  long roundTripFailures() {
    return roundTripFailures;
  }

  /** The parsed share of formula cells, or 1 if there are none. */
  double rate() {
    return formulaCells == 0 ? 1.0 : (double) parsed / formulaCells;
  }

  /** Functions by number of calls, most used first. */
  Map<String, Long> ranked() {
    Map<String, Long> ranked = new LinkedHashMap<>();
    functions.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .forEach(e -> ranked.put(e.getKey(), e.getValue()));
    return ranked;
  }

  /**
   * How many files call each function. One large file can dominate {@link #ranked}, but not this.
   */
  Map<String, Long> filesUsing() {
    return new TreeMap<>(functionFiles);
  }

  long functionCalls() {
    return functionCalls;
  }

  long dataTables() {
    return dataTables;
  }

  List<String> failureExamples() {
    return failureExamples;
  }
}
