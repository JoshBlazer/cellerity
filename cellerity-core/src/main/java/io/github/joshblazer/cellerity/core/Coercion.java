package io.github.joshblazer.cellerity.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalDouble;

/** Excel's conversions between value types. All throw {@link FunctionError} on failure. */
public final class Coercion {
  private static final MathContext FIFTEEN = new MathContext(15, RoundingMode.HALF_UP);

  private Coercion() {}

  /** Reduces a grid to one value by implicit intersection with the formula's cell. */
  public static Value intersect(Value value, FunctionContext context) {
    if (!(value instanceof Value.Grid grid)) {
      return value;
    }
    if (grid.rows() == 1 && grid.columns() == 1) {
      return grid.get(0, 0);
    }
    if (grid instanceof RangeValue range) {
      if (range.columns() == 1
          && context.row() >= range.firstRow()
          && context.row() <= range.lastRow()) {
        return range.get(context.row() - range.firstRow(), 0);
      }
      if (range.rows() == 1
          && context.column() >= range.firstColumn()
          && context.column() <= range.lastColumn()) {
        return range.get(0, context.column() - range.firstColumn());
      }
      return new Value.Err(ExcelError.VALUE);
    }
    return grid.get(0, 0);
  }

  /** The top-left value of a grid, or the value itself. */
  public static Value first(Value value) {
    return value instanceof Value.Grid grid ? grid.get(0, 0) : value;
  }

  public static double toNumber(Value value) {
    return switch (first(value)) {
      case Value.Num n -> n.value();
      case Value.Bool b -> b.value() ? 1 : 0;
      case Value.Blank blank -> 0;
      case Value.Err e -> throw new FunctionError(e.error());
      case Value.Text t -> {
        OptionalDouble parsed = parseNumber(t.value());
        if (parsed.isEmpty()) {
          throw new FunctionError(ExcelError.VALUE);
        }
        yield parsed.getAsDouble();
      }
      case Value.Grid grid -> throw new FunctionError(ExcelError.VALUE);
    };
  }

  public static String toText(Value value) {
    return switch (first(value)) {
      case Value.Num n -> numberToText(n.value());
      case Value.Bool b -> b.value() ? "TRUE" : "FALSE";
      case Value.Blank blank -> "";
      case Value.Text t -> t.value();
      case Value.Err e -> throw new FunctionError(e.error());
      case Value.Grid grid -> throw new FunctionError(ExcelError.VALUE);
    };
  }

  public static boolean toBool(Value value) {
    return switch (first(value)) {
      case Value.Num n -> n.value() != 0;
      case Value.Bool b -> b.value();
      case Value.Blank blank -> false;
      case Value.Err e -> throw new FunctionError(e.error());
      case Value.Text t -> {
        if (t.value().equalsIgnoreCase("TRUE")) {
          yield true;
        }
        if (t.value().equalsIgnoreCase("FALSE")) {
          yield false;
        }
        throw new FunctionError(ExcelError.VALUE);
      }
      case Value.Grid grid -> throw new FunctionError(ExcelError.VALUE);
    };
  }

  /** Reads text as a number the way Excel does, including currency, percent, and dates. */
  public static OptionalDouble parseNumber(String text) {
    String t = text.strip();
    if (t.isEmpty()) {
      return OptionalDouble.empty();
    }
    boolean percent = t.endsWith("%");
    if (percent) {
      t = t.substring(0, t.length() - 1).strip();
    }
    boolean negative = false;
    if (t.startsWith("(") && t.endsWith(")")) {
      negative = true;
      t = t.substring(1, t.length() - 1).strip();
    }
    if (t.startsWith("-")) {
      negative = !negative;
      t = t.substring(1).strip();
    } else if (t.startsWith("+")) {
      t = t.substring(1).strip();
    }
    if (t.startsWith("$")) {
      t = t.substring(1).strip();
    }
    String plain =
        t.indexOf(',') > 0 && t.matches("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]*)?([eE].*)?")
            ? t.replace(",", "")
            : t;
    if (plain.matches("([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")) {
      double value = Double.parseDouble(plain);
      value = percent ? value / 100 : value;
      return OptionalDouble.of(negative ? -value : value);
    }
    if (percent || negative) {
      return OptionalDouble.empty();
    }
    return Dates.parse(text, DateSystem.SYSTEM_1900);
  }

  /** A number as text, at Excel's 15 significant digits, as {@code &} and {@code TEXT} give it. */
  public static String numberToText(double value) {
    if (value == 0) {
      return "0";
    }
    BigDecimal rounded = new BigDecimal(value).round(FIFTEEN).stripTrailingZeros();
    int exponent = rounded.precision() - rounded.scale() - 1;
    if (exponent >= 15 || exponent < -9) {
      String digits = rounded.unscaledValue().abs().toString();
      String mantissa =
          digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
      return (value < 0 ? "-" : "")
          + mantissa
          + "E"
          + (exponent < 0 ? "-" : "+")
          + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
    }
    return rounded.toPlainString();
  }
}
