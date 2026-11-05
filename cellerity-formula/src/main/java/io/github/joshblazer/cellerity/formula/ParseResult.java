package io.github.joshblazer.cellerity.formula;

import java.util.List;

/**
 * The outcome of parsing. {@code expr} is always present, and contains {@link Expr.Invalid} nodes
 * where recovery happened.
 */
public record ParseResult(Expr expr, List<Diagnostic> diagnostics) {
  public ParseResult {
    diagnostics = List.copyOf(diagnostics);
  }

  /** True if the formula parsed with no problems. */
  public boolean ok() {
    return diagnostics.isEmpty();
  }
}
