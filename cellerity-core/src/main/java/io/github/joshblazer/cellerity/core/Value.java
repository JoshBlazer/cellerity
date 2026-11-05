package io.github.joshblazer.cellerity.core;

/** A value. Everything except {@link Grid} can be stored in a cell. */
public sealed interface Value {

  /** An IEEE 754 double. Dates are numbers; see {@code docs/design/numerics.md}. */
  record Num(double value) implements Value {}

  /** A string. */
  record Text(String value) implements Value {}

  /** {@code TRUE} or {@code FALSE}. */
  record Bool(boolean value) implements Value {}

  /** An Excel error as a first-class value. */
  record Err(ExcelError error) implements Value {}

  /** An empty cell. */
  record Blank() implements Value {}

  /** A rectangle of values: an array, or a live view of a sheet range. */
  non-sealed interface Grid extends Value {
    int rows();

    int columns();

    /** The value at a 0-based position, which is never a grid. */
    Value get(int row, int column);

    /** How many rows hold anything. Iterating further only finds blanks. */
    default int usedRows() {
      return rows();
    }

    /** How many columns hold anything. Iterating further only finds blanks. */
    default int usedColumns() {
      return columns();
    }
  }

  /** The shared blank value. */
  Value BLANK = new Blank();
}
