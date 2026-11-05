package io.github.joshblazer.cellerity.core;

import java.util.List;

/** An in-memory array, from an array constant or an array calculation. */
public final class ArrayGrid implements Value.Grid {
  private final int rows;
  private final int columns;
  private final Value[] cells;

  /** Wraps cells in row order. The array must not be changed afterwards. */
  public ArrayGrid(int rows, int columns, Value[] cells) {
    if (cells.length != rows * columns) {
      throw new IllegalArgumentException(rows + "x" + columns + " with " + cells.length + " cells");
    }
    this.rows = rows;
    this.columns = columns;
    this.cells = cells;
  }

  /** A one-column array. */
  public static ArrayGrid column(List<Value> values) {
    return new ArrayGrid(values.size(), 1, values.toArray(new Value[0]));
  }

  @Override
  public int rows() {
    return rows;
  }

  @Override
  public int columns() {
    return columns;
  }

  @Override
  public Value get(int row, int column) {
    return cells[row * columns + column];
  }
}
