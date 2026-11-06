package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.bool;
import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.num;

import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.RangeValue;
import io.github.joshblazer.cellerity.core.Value;

/** Logic and information functions. {@code IF} and its relatives live in the evaluators. */
final class LogicFunctions {
  private LogicFunctions() {}

  static void register(Registry r) {
    r.raw("AND", 1, 255, (a, c) -> logical(a, true));
    r.raw("OR", 1, 255, (a, c) -> logical(a, false));
    r.scalar("NOT", 1, 1, (a, c) -> bool(!Coercion.toBool(a[0])));
    r.scalar("TRUE", 0, 0, (a, c) -> bool(true));
    r.scalar("FALSE", 0, 0, (a, c) -> bool(false));
    r.scalar("NA", 0, 0, (a, c) -> new Value.Err(ExcelError.NA));
    r.scalar("ISNUMBER", 1, 1, (a, c) -> bool(a[0] instanceof Value.Num));
    r.scalar("ISTEXT", 1, 1, (a, c) -> bool(a[0] instanceof Value.Text));
    r.scalar("ISNONTEXT", 1, 1, (a, c) -> bool(!(a[0] instanceof Value.Text)));
    r.scalar("ISLOGICAL", 1, 1, (a, c) -> bool(a[0] instanceof Value.Bool));
    r.scalar("ISBLANK", 1, 1, (a, c) -> bool(a[0] instanceof Value.Blank));
    r.scalar("ISERROR", 1, 1, (a, c) -> bool(a[0] instanceof Value.Err));
    r.scalar("ISNA", 1, 1, (a, c) -> bool(isError(a[0], ExcelError.NA)));
    r.scalar(
        "ISERR", 1, 1, (a, c) -> bool(a[0] instanceof Value.Err && !isError(a[0], ExcelError.NA)));
    r.raw("ISREF", 1, 1, (a, c) -> bool(a[0] instanceof RangeValue));
    r.scalar(
        "ISEVEN", 1, 1, (a, c) -> bool(Math.floor(Math.abs(Coercion.toNumber(a[0]))) % 2 == 0));
    r.scalar("ISODD", 1, 1, (a, c) -> bool(Math.floor(Math.abs(Coercion.toNumber(a[0]))) % 2 == 1));
    r.scalar(
        "N",
        1,
        1,
        (a, c) ->
            switch (a[0]) {
              case Value.Num x -> x;
              case Value.Bool b -> num(b.value() ? 1 : 0);
              case Value.Err e -> e;
              default -> num(0);
            });
    r.scalar(
        "T",
        1,
        1,
        (a, c) ->
            switch (a[0]) {
              case Value.Text t -> t;
              case Value.Err e -> e;
              default -> Args.text("");
            });
    r.raw(
        "TYPE",
        1,
        1,
        (a, c) -> {
          Value v = a[0] instanceof RangeValue range && range.singleCell() ? range.get(0, 0) : a[0];
          return num(
              switch (v) {
                case Value.Num x -> 1;
                case Value.Blank b -> 1;
                case Value.Text t -> 2;
                case Value.Bool b -> 4;
                case Value.Err e -> 16;
                case Value.Grid g -> 64;
              });
        });
    r.scalar(
        "ERROR.TYPE",
        1,
        1,
        (a, c) -> {
          if (!(a[0] instanceof Value.Err e)) {
            throw error(ExcelError.NA);
          }
          return num(
              switch (e.error()) {
                case NULL -> 1;
                case DIV0 -> 2;
                case VALUE -> 3;
                case REF -> 4;
                case NAME -> 5;
                case NUM -> 6;
                case NA -> 7;
                case GETTING_DATA -> 8;
                default -> throw error(ExcelError.NA);
              });
        });
  }

  private static boolean isError(Value value, ExcelError error) {
    return value instanceof Value.Err e && e.error() == error;
  }

  /** {@code AND} and {@code OR}. In a reference, text and blanks are ignored. */
  private static Value logical(Value[] args, boolean all) {
    boolean[] seen = {false};
    boolean[] result = {all};
    for (Value arg : args) {
      if (arg instanceof Value.Grid) {
        Args.each(
            arg,
            v -> {
              if (v instanceof Value.Err e) {
                throw error(e.error());
              }
              if (v instanceof Value.Num || v instanceof Value.Bool) {
                seen[0] = true;
                result[0] = all ? result[0] && Coercion.toBool(v) : result[0] || Coercion.toBool(v);
              }
            });
      } else if (!(arg instanceof Value.Blank)) {
        seen[0] = true;
        boolean b = Coercion.toBool(arg);
        result[0] = all ? result[0] && b : result[0] || b;
      }
    }
    if (!seen[0]) {
      throw error(ExcelError.VALUE);
    }
    return bool(result[0]);
  }
}
