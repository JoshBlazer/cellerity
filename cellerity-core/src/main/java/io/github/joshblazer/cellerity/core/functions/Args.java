package io.github.joshblazer.cellerity.core.functions;

import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionError;
import io.github.joshblazer.cellerity.core.RangeValue;
import io.github.joshblazer.cellerity.core.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Shared argument handling. */
final class Args {
  private Args() {}

  static Value num(double value) {
    return new Value.Num(value);
  }

  static Value bool(boolean value) {
    return new Value.Bool(value);
  }

  static Value text(String value) {
    return new Value.Text(value);
  }

  static double n(Value[] args, int i) {
    return Coercion.toNumber(args[i]);
  }

  static double n(Value[] args, int i, double fallback) {
    return i < args.length && !(args[i] instanceof Value.Blank) ? n(args, i) : fallback;
  }

  static String s(Value[] args, int i) {
    return Coercion.toText(args[i]);
  }

  static FunctionError error(ExcelError error) {
    return new FunctionError(error);
  }

  /** Every value of an argument, in row order, stopping at the used part of a range. */
  static void each(Value arg, Consumer<Value> action) {
    if (arg instanceof Value.Grid grid) {
      int rows = grid.usedRows();
      int columns = grid.usedColumns();
      for (int r = 0; r < rows; r++) {
        for (int c = 0; c < columns; c++) {
          action.accept(grid.get(r, c));
        }
      }
    } else {
      action.accept(arg);
    }
  }

  /**
   * The numbers of arguments, by Excel's rule for aggregates. A direct argument is coerced, while
   * inside a reference or array only numbers count. Errors propagate from either.
   */
  static void numbers(Value[] args, DoubleConsumer action) {
    for (Value arg : args) {
      if (arg instanceof Value.Grid) {
        each(
            arg,
            v -> {
              if (v instanceof Value.Num number) {
                action.accept(number.value());
              } else if (v instanceof Value.Err e) {
                throw error(e.error());
              }
            });
      } else if (!(arg instanceof Value.Blank)) {
        action.accept(Coercion.toNumber(arg));
      }
    }
  }

  /** As {@link #numbers}, but as an array. */
  static double[] numberArray(Value... args) {
    List<Double> values = new ArrayList<>();
    numbers(args, values::add);
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** The "A" aggregates: inside a reference, booleans count too, and text counts as 0. */
  static double[] numberArrayA(Value[] args) {
    List<Double> values = new ArrayList<>();
    for (Value arg : args) {
      if (arg instanceof Value.Grid) {
        each(
            arg,
            v -> {
              switch (v) {
                case Value.Num number -> values.add(number.value());
                case Value.Bool b -> values.add(b.value() ? 1.0 : 0.0);
                case Value.Text t -> values.add(0.0);
                case Value.Err e -> throw error(e.error());
                default -> {}
              }
            });
      } else if (!(arg instanceof Value.Blank)) {
        values.add(Coercion.toNumber(arg));
      }
    }
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** The value's reference, or {@code #VALUE!} if it is not one. */
  static RangeValue reference(Value value) {
    if (value instanceof RangeValue range) {
      return range;
    }
    if (value instanceof Value.Err e) {
      throw error(e.error());
    }
    throw error(ExcelError.VALUE);
  }
}
