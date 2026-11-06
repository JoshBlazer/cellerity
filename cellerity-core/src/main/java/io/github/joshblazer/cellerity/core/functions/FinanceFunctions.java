package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.n;
import static io.github.joshblazer.cellerity.core.functions.Args.num;

import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class FinanceFunctions {
  private static final int MAX_ITERATIONS = 200;
  private static final double CONVERGED = 1e-13;

  private FinanceFunctions() {}

  static void register(Registry r) {
    r.scalar("PMT", 3, 5, (a, c) -> num(pmt(n(a, 0), n(a, 1), n(a, 2), n(a, 3, 0), n(a, 4, 0))));
    r.scalar("PV", 3, 5, (a, c) -> num(pv(n(a, 0), n(a, 1), n(a, 2), n(a, 3, 0), n(a, 4, 0))));
    r.scalar("FV", 3, 5, (a, c) -> num(fv(n(a, 0), n(a, 1), n(a, 2), n(a, 3, 0), n(a, 4, 0))));
    r.scalar(
        "NPER",
        3,
        5,
        (a, c) -> {
          double rate = n(a, 0);
          double payment = n(a, 1);
          double present = n(a, 2);
          double future = n(a, 3, 0);
          double type = n(a, 4, 0);
          if (rate == 0) {
            return num(-(present + future) / payment);
          }
          double adjusted = payment * (1 + rate * type);
          return num(
              Math.log((adjusted - future * rate) / (adjusted + present * rate))
                  / Math.log(1 + rate));
        });
    r.scalar(
        "IPMT",
        4,
        6,
        (a, c) -> num(ipmt(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4, 0), n(a, 5, 0))));
    r.scalar(
        "PPMT",
        4,
        6,
        (a, c) -> {
          double payment = pmt(n(a, 0), n(a, 2), n(a, 3), n(a, 4, 0), n(a, 5, 0));
          return num(payment - ipmt(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4, 0), n(a, 5, 0)));
        });
    r.scalar(
        "RATE",
        3,
        6,
        (a, c) -> num(rate(n(a, 0), n(a, 1), n(a, 2), n(a, 3, 0), n(a, 4, 0), n(a, 5, 0.1))));
    r.raw(
        "NPV",
        2,
        255,
        (a, c) -> {
          double rate = n(a, 0);
          double[] flows = Args.numberArray(Arrays.copyOfRange(a, 1, a.length));
          double total = 0;
          for (int i = 0; i < flows.length; i++) {
            total += flows[i] / Math.pow(1 + rate, i + 1.0);
          }
          return num(total);
        });
    r.raw(
        "IRR",
        1,
        2,
        (a, c) -> {
          double[] flows = Args.numberArray(a[0]);
          return num(solve(x -> irrValue(flows, x), n(a, 1, 0.1), hasBothSigns(flows)));
        });
    r.raw(
        "XNPV",
        3,
        3,
        (a, c) -> {
          double rate = n(a, 0);
          if (rate <= -1) {
            throw error(ExcelError.NUM);
          }
          double[][] flows = dated(a[1], a[2]);
          return num(xnpv(rate, flows[0], flows[1]));
        });
    r.raw(
        "XIRR",
        2,
        3,
        (a, c) -> {
          double[][] flows = dated(a[0], a[1]);
          return num(solve(x -> xnpv(x, flows[0], flows[1]), n(a, 2, 0.1), hasBothSigns(flows[0])));
        });
    r.scalar(
        "SLN",
        3,
        3,
        (a, c) -> {
          if (n(a, 2) == 0) {
            throw error(ExcelError.DIV0);
          }
          return num((n(a, 0) - n(a, 1)) / n(a, 2));
        });
    r.scalar(
        "SYD",
        4,
        4,
        (a, c) -> {
          double life = n(a, 2);
          double period = n(a, 3);
          if (life <= 0 || period < 1 || period > life) {
            throw error(ExcelError.NUM);
          }
          return num((n(a, 0) - n(a, 1)) * (life - period + 1) * 2 / (life * (life + 1)));
        });
    r.scalar("DB", 4, 5, (a, c) -> num(db(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4, 12))));
    r.scalar("DDB", 4, 5, (a, c) -> num(ddb(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4, 2))));
  }

  private static double fv(double r, double periods, double payment, double present, double type) {
    if (r == 0) {
      return -(present + payment * periods);
    }
    double growth = Math.pow(1 + r, periods);
    return -(present * growth + payment * (1 + r * type) * (growth - 1) / r);
  }

  private static double pv(double r, double periods, double payment, double future, double type) {
    if (r == 0) {
      return -(future + payment * periods);
    }
    double growth = Math.pow(1 + r, periods);
    return -(future + payment * (1 + r * type) * (growth - 1) / r) / growth;
  }

  private static double pmt(double r, double periods, double present, double future, double type) {
    if (periods == 0) {
      throw error(ExcelError.NUM);
    }
    if (r == 0) {
      return -(present + future) / periods;
    }
    double growth = Math.pow(1 + r, periods);
    return -(future + present * growth) * r / ((1 + r * type) * (growth - 1));
  }

  private static double ipmt(
      double r, double period, double periods, double present, double future, double type) {
    if (period < 1 || period > periods) {
      throw error(ExcelError.NUM);
    }
    if (type != 0 && period == 1) {
      return 0;
    }
    double payment = pmt(r, periods, present, future, type);
    double interest = fv(r, period - 1, payment, present, type) * r;
    return type != 0 ? interest / (1 + r) : interest;
  }

  private static double rate(
      double periods, double payment, double present, double future, double type, double guess) {
    return solve(
        x -> {
          if (x == 0) {
            return present + payment * periods + future;
          }
          double growth = Math.pow(1 + x, periods);
          return present * growth + payment * (1 + x * type) * (growth - 1) / x + future;
        },
        guess,
        true);
  }

  private static double irrValue(double[] flows, double x) {
    double total = 0;
    for (int i = 0; i < flows.length; i++) {
      total += flows[i] / Math.pow(1 + x, i);
    }
    return total;
  }

  private static double xnpv(double rate, double[] values, double[] dates) {
    double total = 0;
    for (int i = 0; i < values.length; i++) {
      total += values[i] / Math.pow(1 + rate, (dates[i] - dates[0]) / 365);
    }
    return total;
  }

  /** Values and whole-day dates, which must pair up. */
  private static double[][] dated(Value values, Value dates) {
    double[] v = strictNumbers(values);
    double[] d = strictNumbers(dates);
    if (v.length != d.length || v.length == 0) {
      throw error(ExcelError.NUM);
    }
    for (int i = 0; i < d.length; i++) {
      d[i] = Math.floor(d[i]);
      if (d[i] < d[0]) {
        throw error(ExcelError.NUM);
      }
    }
    return new double[][] {v, d};
  }

  private static double[] strictNumbers(Value arg) {
    List<Double> out = new ArrayList<>();
    Args.each(
        arg,
        v -> {
          switch (v) {
            case Value.Num x -> out.add(x.value());
            case Value.Blank b -> out.add(0.0);
            case Value.Err e -> throw error(e.error());
            default -> throw error(ExcelError.VALUE);
          }
        });
    return out.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private static boolean hasBothSigns(double[] flows) {
    boolean positive = false;
    boolean negative = false;
    for (double flow : flows) {
      positive |= flow > 0;
      negative |= flow < 0;
    }
    return positive && negative;
  }

  private interface Curve {
    double at(double x);
  }

  /** Secant iteration from the guess, converged far tighter than Excel's stated 1E-7. */
  private static double solve(Curve f, double guess, boolean solvable) {
    if (!solvable) {
      throw error(ExcelError.NUM);
    }
    double x0 = guess;
    double x1 = guess == 0 ? 0.01 : guess * 1.01;
    double y0 = f.at(x0);
    for (int i = 0; i < MAX_ITERATIONS; i++) {
      double y1 = f.at(x1);
      if (Double.isNaN(y1) || Double.isInfinite(y1)) {
        x1 = (x0 + x1) / 2;
        continue;
      }
      if (y1 == y0) {
        break;
      }
      double next = x1 - y1 * (x1 - x0) / (y1 - y0);
      if (next <= -1) {
        next = (x1 - 1) / 2;
      }
      if (Math.abs(next - x1) < CONVERGED * Math.max(1, Math.abs(next))) {
        return next;
      }
      x0 = x1;
      y0 = y1;
      x1 = next;
    }
    if (Math.abs(f.at(x1)) < 1e-6) {
      return x1;
    }
    throw error(ExcelError.NUM);
  }

  private static double db(double cost, double salvage, double life, double period, double month) {
    if (cost < 0 || salvage < 0 || life <= 0 || period < 1 || month < 1 || month > 12) {
      throw error(ExcelError.NUM);
    }
    if (period > life + (month < 12 ? 1 : 0)) {
      throw error(ExcelError.NUM);
    }
    if (cost == 0) {
      return 0;
    }
    double rate =
        MathFunctions.round(
            1 - Math.pow(salvage / cost, 1 / life), 3, java.math.RoundingMode.HALF_UP);
    double depreciation = cost * rate * month / 12;
    if (period == 1) {
      return depreciation;
    }
    double total = depreciation;
    for (int i = 2; i <= (int) period; i++) {
      depreciation =
          i == (int) life + 1 ? (cost - total) * rate * (12 - month) / 12 : (cost - total) * rate;
      total += depreciation;
    }
    return depreciation;
  }

  private static double ddb(
      double cost, double salvage, double life, double period, double factor) {
    if (cost < 0 || salvage < 0 || life <= 0 || period < 1 || period > life || factor <= 0) {
      throw error(ExcelError.NUM);
    }
    double book = cost;
    double depreciation = 0;
    for (int i = 1; i <= (int) period; i++) {
      depreciation = Math.min(book * factor / life, Math.max(book - salvage, 0));
      book -= depreciation;
    }
    return depreciation;
  }
}
