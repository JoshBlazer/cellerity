package io.github.joshblazer.cellerity.core;

import java.util.Optional;

/**
 * A cell position, 1-based: column 1 is {@code A}, and row 1 is the first row.
 *
 * @param column 1 to {@link #MAX_COLUMN}
 * @param row 1 to {@link #MAX_ROW}
 */
public record CellRef(int column, int row) implements Comparable<CellRef> {
  public static final int MAX_COLUMN = 16_384;
  public static final int MAX_ROW = 1_048_576;

  public CellRef {
    if (column < 1 || column > MAX_COLUMN || row < 1 || row > MAX_ROW) {
      throw new IllegalArgumentException("Cell out of range: column " + column + ", row " + row);
    }
  }

  /** Parses {@code A1}, {@code $A$1}, and lower-case forms. Returns empty if not a valid cell. */
  public static Optional<CellRef> parse(String text) {
    throw new UnsupportedOperationException("not implemented");
  }

  /** The A1 form, without dollar signs. */
  public String toA1() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int compareTo(CellRef other) {
    int byRow = Integer.compare(row, other.row);
    return byRow != 0 ? byRow : Integer.compare(column, other.column);
  }

  @Override
  public String toString() {
    return "CellRef(" + column + "," + row + ")";
  }
}
