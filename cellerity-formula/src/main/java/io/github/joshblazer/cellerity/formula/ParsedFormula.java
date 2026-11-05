package io.github.joshblazer.cellerity.formula;

import java.util.List;
import java.util.Optional;

/**
 * A formula cell ready to evaluate.
 *
 * @param expr the tree, already shifted for shared followers
 * @param arrayRange for array formulas, the range the result fills
 * @param diagnostics parse problems; empty if the formula parsed cleanly
 */
public record ParsedFormula(
    Kind kind, Expr expr, Optional<String> arrayRange, List<Diagnostic> diagnostics) {
  /** How the formula is stored. */
  public enum Kind {
    NORMAL,
    SHARED,
    ARRAY_MASTER,
    /** A data table, which is not evaluated in v1. */
    DATA_TABLE
  }

  public boolean ok() {
    return diagnostics.isEmpty();
  }
}
