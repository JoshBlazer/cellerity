package io.github.joshblazer.cellerity.truffle;

import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.interp.FormulaEngine;
import io.github.joshblazer.cellerity.interp.Workspace;

/** Evaluates formulas as Truffle nodes. See {@code docs/design/truffle.md}. */
public final class TruffleEngine implements FormulaEngine {
  @Override
  public Value evaluate(
      Expr expr, int sheet, int column, int row, boolean arrayFormula, Workspace workspace) {
    throw new UnsupportedOperationException("not implemented");
  }

  /** How many nodes have been built. */
  public long nodes() {
    throw new UnsupportedOperationException("not implemented");
  }

  /** How many of those hand a subtree to the interpreter. */
  public long delegatedNodes() {
    throw new UnsupportedOperationException("not implemented");
  }

  /** The Truffle runtime in use. Only an optimizing one can JIT-compile (ADR-0006). */
  public static String runtimeName() {
    throw new UnsupportedOperationException("not implemented");
  }
}
