package io.github.joshblazer.cellerity.core;

/**
 * A live view of a sheet rectangle, with 1-based bounds. A single-cell reference also travels as
 * one, because Excel treats a value differently depending on whether it arrived by reference.
 */
public final class RangeValue implements Value.Grid {
  private final CellSource source;
  private final int sheet;
  private final int firstColumn;
  private final int firstRow;
  private final int lastColumn;
  private final int lastRow;

  public RangeValue(
      CellSource source, int sheet, int firstColumn, int firstRow, int lastColumn, int lastRow) {
    this.source = source;
    this.sheet = sheet;
    this.firstColumn = Math.min(firstColumn, lastColumn);
    this.firstRow = Math.min(firstRow, lastRow);
    this.lastColumn = Math.max(firstColumn, lastColumn);
    this.lastRow = Math.max(firstRow, lastRow);
  }

  public CellSource source() {
    return source;
  }

  public int sheet() {
    return sheet;
  }

  public int firstColumn() {
    return firstColumn;
  }

  public int firstRow() {
    return firstRow;
  }

  public int lastColumn() {
    return lastColumn;
  }

  public int lastRow() {
    return lastRow;
  }

  public boolean singleCell() {
    return firstColumn == lastColumn && firstRow == lastRow;
  }

  @Override
  public int rows() {
    return lastRow - firstRow + 1;
  }

  @Override
  public int columns() {
    return lastColumn - firstColumn + 1;
  }

  @Override
  public int usedRows() {
    return Math.max(1, Math.min(rows(), source.lastRow(sheet) - firstRow + 1));
  }

  @Override
  public int usedColumns() {
    return Math.max(1, Math.min(columns(), source.lastColumn(sheet) - firstColumn + 1));
  }

  @Override
  public Value get(int row, int column) {
    return source.cell(sheet, firstColumn + column, firstRow + row);
  }
}
