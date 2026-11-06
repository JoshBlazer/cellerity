package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.Expr;

/** Evaluates one formula. The interpreter is the reference; others must agree with it. */
public interface FormulaEngine {
  /**
   * Evaluates a formula at a cell.
   *
   * @param arrayFormula whether this is an array formula, whose result may be a grid
   * @return a single value, or a grid if {@code arrayFormula}
   * @throws UnsupportedFeature for anything out of scope
   */
  Value evaluate(
      Expr expr, int sheet, int column, int row, boolean arrayFormula, Workspace workspace);
}
