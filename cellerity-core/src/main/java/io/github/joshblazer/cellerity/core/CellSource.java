package io.github.joshblazer.cellerity.core;

/** Where cell values come from. Evaluators implement this, and may calculate on demand. */
public interface CellSource {
  /** The value of a 1-based cell, or blank. Never a grid. */
  Value cell(int sheet, int column, int row);

  /** The last row of this sheet holding anything, or 0. */
  int lastRow(int sheet);

  /** The last column of this sheet holding anything, or 0. */
  int lastColumn(int sheet);
}
