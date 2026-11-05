package io.github.joshblazer.cellerity.formula;

/** Moves relative references, as Excel does when a shared formula is applied to another cell. */
public final class ReferenceShifter {
  private ReferenceShifter() {}

  /** The formula with relative endpoints moved. Positions wrap at the sheet limits. */
  public static Expr shift(Expr expr, int columns, int rows) {
    throw new UnsupportedOperationException("not implemented");
  }
}
