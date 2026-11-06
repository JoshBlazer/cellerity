package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.n;
import static io.github.joshblazer.cellerity.core.functions.Args.num;

import io.github.joshblazer.cellerity.core.ArrayGrid;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.DoubleUnaryOperator;

final class MathFunctions {
  private MathFunctions() {}

  static void register(Registry r) {
    r.raw(
        "SUM",
        1,
        255,
        (a, c) -> {
          double[] sum = {0};
          Args.numbers(a, x -> sum[0] += x);
          return num(sum[0]);
        });
    r.raw(
        "PRODUCT",
        1,
        255,
        (a, c) -> {
          double[] product = {1};
          Args.numbers(a, x -> product[0] *= x);
          return num(product[0]);
        });
    r.raw(
        "SUMSQ",
        1,
        255,
        (a, c) -> {
          double[] sum = {0};
          Args.numbers(a, x -> sum[0] += x * x);
          return num(sum[0]);
        });
    r.array("SUMPRODUCT", 1, 255, (a, c) -> sumProduct(a));
    r.array("MMULT", 2, 2, (a, c) -> matrixMultiply(a[0], a[1]));
    r.scalar("ROUND", 2, 2, (a, c) -> num(round(n(a, 0), n(a, 1), RoundingMode.HALF_UP)));
    r.scalar("ROUNDUP", 2, 2, (a, c) -> num(round(n(a, 0), n(a, 1), RoundingMode.UP)));
    r.scalar("ROUNDDOWN", 2, 2, (a, c) -> num(round(n(a, 0), n(a, 1), RoundingMode.DOWN)));
    r.scalar("TRUNC", 1, 2, (a, c) -> num(round(n(a, 0), n(a, 1, 0), RoundingMode.DOWN)));
    r.scalar("INT", 1, 1, (a, c) -> num(Math.floor(n(a, 0))));
    r.scalar("ABS", 1, 1, (a, c) -> num(Math.abs(n(a, 0))));
    r.scalar("SIGN", 1, 1, (a, c) -> num(Math.signum(n(a, 0))));
    r.scalar("SQRT", 1, 1, (a, c) -> num(Math.sqrt(n(a, 0))));
    r.scalar("EXP", 1, 1, (a, c) -> num(Math.exp(n(a, 0))));
    r.scalar("LN", 1, 1, (a, c) -> num(positiveLog(n(a, 0))));
    r.scalar("LOG10", 1, 1, (a, c) -> num(log10(n(a, 0))));
    r.scalar(
        "LOG",
        1,
        2,
        (a, c) -> {
          double base = n(a, 1, 10);
          if (base == 10) {
            return num(log10(n(a, 0)));
          }
          if (base <= 0) {
            throw error(ExcelError.NUM);
          }
          double divisor = Math.log(base);
          if (divisor == 0) {
            throw error(ExcelError.DIV0);
          }
          return num(positiveLog(n(a, 0)) / divisor);
        });
    r.scalar("POWER", 2, 2, (a, c) -> power(a));
    r.scalar(
        "MOD",
        2,
        2,
        (a, c) -> {
          double x = n(a, 0);
          double y = n(a, 1);
          if (y == 0) {
            throw error(ExcelError.DIV0);
          }
          return num(x - y * Math.floor(x / y));
        });
    r.scalar(
        "QUOTIENT",
        2,
        2,
        (a, c) -> {
          if (n(a, 1) == 0) {
            throw error(ExcelError.DIV0);
          }
          return num(round(n(a, 0) / n(a, 1), 0, RoundingMode.DOWN));
        });
    r.scalar("PI", 0, 0, (a, c) -> num(Math.PI));
    r.scalar("RADIANS", 1, 1, (a, c) -> num(n(a, 0) * Math.PI / 180));
    r.scalar("DEGREES", 1, 1, (a, c) -> num(n(a, 0) * 180 / Math.PI));
    unary(r, "SIN", Math::sin);
    unary(r, "COS", Math::cos);
    unary(r, "TAN", Math::tan);
    unary(r, "ASIN", Math::asin);
    unary(r, "ACOS", Math::acos);
    unary(r, "ATAN", Math::atan);
    unary(r, "SINH", Math::sinh);
    unary(r, "COSH", Math::cosh);
    unary(r, "TANH", Math::tanh);
    r.scalar(
        "ATAN2",
        2,
        2,
        (a, c) -> {
          if (n(a, 0) == 0 && n(a, 1) == 0) {
            throw error(ExcelError.DIV0);
          }
          return num(Math.atan2(n(a, 1), n(a, 0)));
        });
    r.scalar("CEILING", 2, 2, (a, c) -> num(toMultiple(n(a, 0), n(a, 1), true)));
    r.scalar("FLOOR", 2, 2, (a, c) -> num(toMultiple(n(a, 0), n(a, 1), false)));
    r.scalar(
        "MROUND",
        2,
        2,
        (a, c) -> {
          double x = n(a, 0);
          double m = n(a, 1);
          if (m == 0) {
            return num(0);
          }
          if (x * m < 0) {
            throw error(ExcelError.NUM);
          }
          return num(cleaned(m * round(x / m, 0, RoundingMode.HALF_UP)));
        });
    r.scalar(
        "EVEN",
        1,
        1,
        (a, c) -> {
          double x = n(a, 0);
          return num(Math.copySign(Math.ceil(Math.abs(x) / 2) * 2, x));
        });
    r.scalar(
        "ODD",
        1,
        1,
        (a, c) -> {
          double x = n(a, 0);
          double up = Math.ceil(Math.abs(x));
          return num(Math.copySign(up % 2 == 0 ? up + 1 : up, x == 0 ? 1 : x));
        });
    r.scalar(
        "FACT",
        1,
        1,
        (a, c) -> {
          double x = Math.floor(n(a, 0));
          if (x < 0) {
            throw error(ExcelError.NUM);
          }
          double product = 1;
          for (int i = 2; i <= x && !Double.isInfinite(product); i++) {
            product *= i;
          }
          return num(product);
        });
    r.scalar(
        "COMBIN",
        2,
        2,
        (a, c) -> {
          double total = Math.floor(n(a, 0));
          double chosen = Math.floor(n(a, 1));
          if (total < 0 || chosen < 0 || chosen > total) {
            throw error(ExcelError.NUM);
          }
          double result = 1;
          double k = Math.min(chosen, total - chosen);
          for (int i = 1; i <= k; i++) {
            result = result * (total - k + i) / i;
          }
          return num(Math.rint(result));
        });
    r.raw("GCD", 1, 255, (a, c) -> num(gcdAll(a)));
    r.raw(
        "LCM",
        1,
        255,
        (a, c) -> {
          double result = 1;
          for (double x : Args.numberArray(a)) {
            double whole = Math.floor(x);
            if (whole < 0) {
              throw error(ExcelError.NUM);
            }
            if (whole == 0) {
              return num(0);
            }
            result = result / gcd(result, whole) * whole;
          }
          return num(result);
        });
    r.scalar("RAND", 0, 0, (a, c) -> num(c.random()));
    r.scalar(
        "RANDBETWEEN",
        2,
        2,
        (a, c) -> {
          double low = Math.ceil(n(a, 0));
          double high = Math.floor(n(a, 1));
          if (low > high) {
            throw error(ExcelError.NUM);
          }
          return num(low + Math.floor(c.random() * (high - low + 1)));
        });
  }

  private static Value power(Value[] a) {
    double x = n(a, 0);
    double y = n(a, 1);
    if (x == 0 && y == 0) {
      throw error(ExcelError.NUM);
    }
    if (x == 0 && y < 0) {
      throw error(ExcelError.DIV0);
    }
    return num(Math.pow(x, y));
  }

  private static void unary(Registry r, String name, DoubleUnaryOperator f) {
    r.scalar(name, 1, 1, (a, c) -> num(f.applyAsDouble(n(a, 0))));
  }

  private static double positiveLog(double x) {
    if (x <= 0) {
      throw error(ExcelError.NUM);
    }
    return Math.log(x);
  }

  private static double log10(double x) {
    if (x <= 0) {
      throw error(ExcelError.NUM);
    }
    return Math.log10(x);
  }

  /**
   * Rounds as Excel appears to, in decimal on the shortest text of the double, so {@code
   * ROUND(2.675,2)} is 2.68 although the stored binary value is slightly below it.
   */
  static double round(double value, double digits, RoundingMode mode) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return value;
    }
    int places = (int) (digits < 0 ? Math.ceil(digits) : Math.floor(digits));
    if (places > 300 || places < -300) {
      return places > 0 ? value : 0;
    }
    return new BigDecimal(Double.toString(value)).setScale(places, mode).doubleValue();
  }

  private static double toMultiple(double x, double multiple, boolean up) {
    if (multiple == 0) {
      if (up) {
        return 0;
      }
      throw error(ExcelError.DIV0);
    }
    if (x > 0 && multiple < 0) {
      throw error(ExcelError.NUM);
    }
    double ratio = cleaned(x / multiple);
    double whole =
        x >= 0 == multiple > 0 || x == 0
            ? (up ? Math.ceil(ratio) : Math.floor(ratio))
            : (up ? Math.floor(ratio) : Math.ceil(ratio));
    return cleaned(whole * multiple);
  }

  /** Removes binary noise past 15 significant digits, as in {@code 1.3/0.1}. */
  private static double cleaned(double value) {
    return Math.abs(value) > 1e15 || value == 0
        ? value
        : new BigDecimal(value).round(new java.math.MathContext(15)).doubleValue();
  }

  private static double gcdAll(Value[] a) {
    double result = 0;
    for (double x : Args.numberArray(a)) {
      if (x < 0) {
        throw error(ExcelError.NUM);
      }
      result = gcd(result, Math.floor(x));
    }
    return result;
  }

  private static double gcd(double x, double y) {
    double a = x;
    double b = y;
    while (b != 0) {
      double t = a % b;
      a = b;
      b = t;
    }
    return a;
  }

  private static Value sumProduct(Value[] args) {
    Value.Grid first = args[0] instanceof Value.Grid g ? g : null;
    int rows = first == null ? 1 : first.usedRows();
    int columns = first == null ? 1 : first.usedColumns();
    for (Value arg : args) {
      int r = arg instanceof Value.Grid g ? g.usedRows() : 1;
      int cl = arg instanceof Value.Grid g ? g.usedColumns() : 1;
      int fullRows = arg instanceof Value.Grid g ? g.rows() : 1;
      int fullColumns = arg instanceof Value.Grid g ? g.columns() : 1;
      int firstFullRows = first == null ? 1 : first.rows();
      int firstFullColumns = first == null ? 1 : first.columns();
      if (fullRows != firstFullRows || fullColumns != firstFullColumns) {
        throw error(ExcelError.VALUE);
      }
      rows = Math.max(rows, r);
      columns = Math.max(columns, cl);
    }
    double sum = 0;
    for (int r = 0; r < rows; r++) {
      for (int cl = 0; cl < columns; cl++) {
        double product = 1;
        for (Value arg : args) {
          Value v = arg instanceof Value.Grid g ? g.get(r, cl) : arg;
          if (v instanceof Value.Err e) {
            throw error(e.error());
          }
          product *= v instanceof Value.Num number ? number.value() : 0;
        }
        sum += product;
      }
    }
    return num(sum);
  }

  private static Value matrixMultiply(Value a, Value b) {
    if (!(a instanceof Value.Grid x) || !(b instanceof Value.Grid y) || x.columns() != y.rows()) {
      throw error(ExcelError.VALUE);
    }
    Value[] cells = new Value[x.rows() * y.columns()];
    for (int r = 0; r < x.rows(); r++) {
      for (int cl = 0; cl < y.columns(); cl++) {
        double sum = 0;
        for (int k = 0; k < x.columns(); k++) {
          if (!(x.get(r, k) instanceof Value.Num p) || !(y.get(k, cl) instanceof Value.Num q)) {
            throw error(ExcelError.VALUE);
          }
          sum += p.value() * q.value();
        }
        cells[r * y.columns() + cl] = num(sum);
      }
    }
    return new ArrayGrid(x.rows(), y.columns(), cells);
  }
}
