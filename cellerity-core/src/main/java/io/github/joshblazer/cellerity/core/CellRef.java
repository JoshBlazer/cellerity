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

  private static final int MAX_COLUMN_LETTERS = 3;

  public CellRef {
    if (column < 1 || column > MAX_COLUMN || row < 1 || row > MAX_ROW) {
      throw new IllegalArgumentException("Cell out of range: column " + column + ", row " + row);
    }
  }

  /** Parses {@code A1}, {@code $A$1}, and lower-case forms. Returns empty if not a valid cell. */
  public static Optional<CellRef> parse(String text) {
    int length = text.length();
    int i = 0;
    if (i < length && text.charAt(i) == '$') {
      i++;
    }
    int lettersStart = i;
    int column = 0;
    while (i < length && isAsciiLetter(text.charAt(i))) {
      if (i - lettersStart == MAX_COLUMN_LETTERS) {
        return Optional.empty();
      }
      column = column * 26 + (Character.toUpperCase(text.charAt(i)) - 'A' + 1);
      i++;
    }
    if (i == lettersStart || column > MAX_COLUMN) {
      return Optional.empty();
    }
    if (i < length && text.charAt(i) == '$') {
      i++;
    }
    int digitsStart = i;
    int row = 0;
    while (i < length && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
      row = row * 10 + (text.charAt(i) - '0');
      if (row > MAX_ROW) {
        return Optional.empty();
      }
      i++;
    }
    if (i == digitsStart || i != length || row < 1) {
      return Optional.empty();
    }
    return Optional.of(new CellRef(column, row));
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  /** The A1 form, without dollar signs. */
  public String toA1() {
    StringBuilder letters = new StringBuilder();
    for (int c = column; c > 0; c = (c - 1) / 26) {
      letters.insert(0, (char) ('A' + (c - 1) % 26));
    }
    return letters.append(row).toString();
  }

  @Override
  public int compareTo(CellRef other) {
    int byRow = Integer.compare(row, other.row);
    return byRow != 0 ? byRow : Integer.compare(column, other.column);
  }

  @Override
  public String toString() {
    return toA1();
  }
}
