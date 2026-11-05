package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.core.CellRef;

/** Moves relative references, as Excel does when a shared formula is applied to another cell. */
public final class ReferenceShifter {
  private ReferenceShifter() {}

  /** The formula with relative endpoints moved. Positions wrap at the sheet limits. */
  public static Expr shift(Expr expr, int columns, int rows) {
    return Exprs.mapLeaves(
        expr,
        leaf ->
            switch (leaf) {
              case Expr.Cell c -> new Expr.Cell(c.scope(), move(c.at(), columns, rows), c.span());
              case Expr.Area a ->
                  new Expr.Area(
                      a.scope(),
                      move(a.from(), columns, rows),
                      move(a.to(), columns, rows),
                      a.span());
              default -> leaf;
            });
  }

  private static Expr.Endpoint move(Expr.Endpoint e, int columns, int rows) {
    int column = e.col() == 0 || e.colAbs() ? e.col() : wrap(e.col(), columns, CellRef.MAX_COLUMN);
    int row = e.row() == 0 || e.rowAbs() ? e.row() : wrap(e.row(), rows, CellRef.MAX_ROW);
    return new Expr.Endpoint(column, row, e.colAbs(), e.rowAbs());
  }

  private static int wrap(int position, int delta, int max) {
    return Math.floorMod(position - 1 + delta, max) + 1;
  }
}
