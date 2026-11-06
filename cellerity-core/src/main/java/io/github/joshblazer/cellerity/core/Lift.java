package io.github.joshblazer.cellerity.core;

import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Applies scalar operations across grids, element by element, with Excel's broadcasting. */
public final class Lift {
  private static final Value OUTSIDE = new Value.Err(ExcelError.NA);

  private Lift() {}

  /** Prepares an operand: a single cell becomes its value, and so may a range. */
  public static Value prepare(Value value, FunctionContext context) {
    if (!(value instanceof Value.Grid grid)) {
      return value;
    }
    if (grid.rows() == 1 && grid.columns() == 1) {
      return grid.get(0, 0);
    }
    if (grid instanceof RangeValue && !context.arrayMode()) {
      return Coercion.intersect(grid, context);
    }
    return grid;
  }

  /** The element at a position, repeating single rows and columns, and {@code #N/A} outside. */
  public static Value element(Value value, int row, int column) {
    if (!(value instanceof Value.Grid grid)) {
      return value;
    }
    int r = grid.rows() == 1 ? 0 : row;
    int c = grid.columns() == 1 ? 0 : column;
    return r >= grid.rows() || c >= grid.columns() ? OUTSIDE : grid.get(r, c);
  }

  public static Value unary(Value operand, FunctionContext context, UnaryOperator<Value> op) {
    Value prepared = prepare(operand, context);
    if (!(prepared instanceof Value.Grid grid)) {
      return op.apply(prepared);
    }
    int rows = grid.usedRows();
    int columns = grid.usedColumns();
    Value[] cells = new Value[rows * columns];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < columns; c++) {
        cells[r * columns + c] = op.apply(grid.get(r, c));
      }
    }
    return new ArrayGrid(rows, columns, cells);
  }

  public static Value binary(
      Value left, Value right, FunctionContext context, BinaryOperator<Value> op) {
    Value a = prepare(left, context);
    Value b = prepare(right, context);
    if (!(a instanceof Value.Grid) && !(b instanceof Value.Grid)) {
      return op.apply(a, b);
    }
    int rows = Math.max(rowsOf(a), rowsOf(b));
    int columns = Math.max(columnsOf(a), columnsOf(b));
    Value[] cells = new Value[rows * columns];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < columns; c++) {
        cells[r * columns + c] = op.apply(element(a, r, c), element(b, r, c));
      }
    }
    return new ArrayGrid(rows, columns, cells);
  }

  /** Applies a scalar function, lifting it over any grid among its arguments. */
  public static Value call(
      Value[] args, FunctionContext context, Function<Value[], Value> function) {
    Value[] prepared = new Value[args.length];
    int rows = 0;
    int columns = 0;
    for (int i = 0; i < args.length; i++) {
      prepared[i] = prepare(args[i], context);
      if (prepared[i] instanceof Value.Grid) {
        rows = Math.max(rows, rowsOf(prepared[i]));
        columns = Math.max(columns, columnsOf(prepared[i]));
      }
    }
    if (rows == 0) {
      return function.apply(prepared);
    }
    Value[] cells = new Value[rows * columns];
    Value[] scalars = new Value[args.length];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < columns; c++) {
        for (int i = 0; i < args.length; i++) {
          scalars[i] = element(prepared[i], r, c);
        }
        cells[r * columns + c] = function.apply(scalars);
      }
    }
    return new ArrayGrid(rows, columns, cells);
  }

  private static int rowsOf(Value value) {
    return value instanceof Value.Grid grid ? grid.usedRows() : 1;
  }

  private static int columnsOf(Value value) {
    return value instanceof Value.Grid grid ? grid.usedColumns() : 1;
  }
}
