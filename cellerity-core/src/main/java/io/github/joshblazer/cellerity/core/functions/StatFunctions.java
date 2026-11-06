package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.n;
import static io.github.joshblazer.cellerity.core.functions.Args.num;

import io.github.joshblazer.cellerity.core.ArrayGrid;
import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.RangeValue;
import io.github.joshblazer.cellerity.core.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Predicate;

final class StatFunctions {
  private StatFunctions() {}

  static void register(Registry r) {
    r.raw("AVERAGE", 1, 255, (a, c) -> num(mean(Args.numberArray(a))));
    r.raw("AVERAGEA", 1, 255, (a, c) -> num(mean(Args.numberArrayA(a))));
    r.raw("COUNT", 1, 255, (a, c) -> num(count(a)));
    r.raw("COUNTA", 1, 255, (a, c) -> num(countA(a)));
    r.raw(
        "COUNTBLANK",
        1,
        1,
        (a, c) -> {
          Value.Grid g = grid(a[0]);
          int[] used = {0};
          Args.each(
              g,
              v -> {
                if (!(v instanceof Value.Blank)
                    && !(v instanceof Value.Text t && t.value().isEmpty())) {
                  used[0]++;
                }
              });
          return num((double) g.rows() * g.columns() - used[0]);
        });
    r.raw("MAX", 1, 255, (a, c) -> num(max(Args.numberArray(a))));
    r.raw("MIN", 1, 255, (a, c) -> num(min(Args.numberArray(a))));
    r.raw("MAXA", 1, 255, (a, c) -> num(max(Args.numberArrayA(a))));
    r.raw("MINA", 1, 255, (a, c) -> num(min(Args.numberArrayA(a))));
    r.raw(
        "MEDIAN",
        1,
        255,
        (a, c) -> {
          double[] v = sorted(Args.numberArray(a));
          if (v.length == 0) {
            throw error(ExcelError.NUM);
          }
          int mid = v.length / 2;
          return num(v.length % 2 == 1 ? v[mid] : (v[mid - 1] + v[mid]) / 2);
        });
    r.raw(
        "MODE",
        1,
        255,
        (a, c) -> {
          for (Value arg : a) {
            Value single =
                arg instanceof Value.Grid g && g.rows() == 1 && g.columns() == 1
                    ? g.get(0, 0)
                    : arg;
            if (single instanceof Value.Text) {
              throw error(ExcelError.VALUE);
            }
          }
          Map<Double, Integer> counts = new LinkedHashMap<>();
          for (double x : Args.numberArray(a)) {
            counts.merge(x + 0.0, 1, Integer::sum);
          }
          double best = Double.NaN;
          int bestCount = 1;
          for (Map.Entry<Double, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
              best = e.getKey();
              bestCount = e.getValue();
            }
          }
          if (bestCount == 1) {
            throw error(ExcelError.NA);
          }
          return num(best);
        });
    r.raw(
        "LARGE",
        2,
        2,
        (a, c) -> {
          double[] v = sorted(Args.numberArray(a[0]));
          int k = rank(n(a, 1), v.length);
          return num(v[v.length - k]);
        });
    r.raw(
        "SMALL",
        2,
        2,
        (a, c) -> {
          double[] v = sorted(Args.numberArray(a[0]));
          int k = rank(n(a, 1), v.length);
          return num(v[k - 1]);
        });
    r.raw(
        "RANK",
        2,
        3,
        (a, c) -> {
          double target = n(a, 0);
          boolean ascending = n(a, 2, 0) != 0;
          double[] v = Args.numberArray(a[1]);
          int better = 0;
          boolean found = false;
          for (double x : v) {
            found |= x == target;
            if (ascending ? x < target : x > target) {
              better++;
            }
          }
          if (!found) {
            throw error(ExcelError.NA);
          }
          return num(better + 1);
        });
    r.raw("PERCENTILE", 2, 2, (a, c) -> num(percentile(Args.numberArray(a[0]), n(a, 1))));
    r.raw(
        "QUARTILE",
        2,
        2,
        (a, c) -> {
          double q = Math.floor(n(a, 1));
          if (q < 0 || q > 4) {
            throw error(ExcelError.NUM);
          }
          return num(percentile(Args.numberArray(a[0]), q / 4));
        });
    r.raw("STDEV", 1, 255, (a, c) -> num(Math.sqrt(variance(Args.numberArray(a), 1))));
    r.raw("STDEVP", 1, 255, (a, c) -> num(Math.sqrt(variance(Args.numberArray(a), 0))));
    r.raw("VAR", 1, 255, (a, c) -> num(variance(Args.numberArray(a), 1)));
    r.raw("VARP", 1, 255, (a, c) -> num(variance(Args.numberArray(a), 0)));
    r.array(
        "CORREL",
        2,
        2,
        (a, c) -> {
          double[][] xy = pairs(a[0], a[1]);
          return num(covariance(xy[0], xy[1]) / Math.sqrt(sxx(xy[0]) * sxx(xy[1])));
        });
    r.array(
        "SLOPE",
        2,
        2,
        (a, c) -> {
          double[][] yx = pairs(a[0], a[1]);
          return num(division(covariance(yx[1], yx[0]), sxx(yx[1])));
        });
    r.array(
        "INTERCEPT",
        2,
        2,
        (a, c) -> {
          double[][] yx = pairs(a[0], a[1]);
          double slope = division(covariance(yx[1], yx[0]), sxx(yx[1]));
          return num(mean(yx[0]) - slope * mean(yx[1]));
        });
    r.scalar("NORMSDIST", 1, 1, (a, c) -> num(normalCdf(n(a, 0))));
    r.scalar(
        "NORMDIST",
        4,
        4,
        (a, c) -> {
          double sd = n(a, 2);
          if (sd <= 0) {
            throw error(ExcelError.NUM);
          }
          double z = (n(a, 0) - n(a, 1)) / sd;
          return num(
              n(a, 3) != 0 ? normalCdf(z) : Math.exp(-z * z / 2) / (sd * Math.sqrt(2 * Math.PI)));
        });
    r.scalar(
        "NORMSINV",
        1,
        1,
        (a, c) -> {
          double p = n(a, 0);
          if (p <= 0 || p >= 1) {
            throw error(ExcelError.NUM);
          }
          return num(normalInverse(p));
        });
    r.raw("SUBTOTAL", 2, 255, (a, c) -> subtotal(a));
    r.raw("COUNTIF", 2, 2, (a, c) -> forCriteria(a, 1, c, (x, d) -> num(countIfs(x))));
    r.raw("COUNTIFS", 2, 254, (a, c) -> forCriteria(a, 1, c, (x, d) -> num(countIfs(x))));
    r.raw(
        "DEVSQ",
        1,
        255,
        (a, c) -> {
          double[] v = Args.numberArray(a);
          if (v.length == 0) {
            throw error(ExcelError.NUM);
          }
          return num(sxx(v));
        });
    r.raw(
        "AVEDEV",
        1,
        255,
        (a, c) -> {
          double[] v = Args.numberArray(a);
          if (v.length == 0) {
            throw error(ExcelError.NUM);
          }
          double m = mean(v);
          return num(Arrays.stream(v).map(x -> Math.abs(x - m)).sum() / v.length);
        });
    pairSum(r, "SUMX2MY2", (x, y) -> x * x - y * y);
    pairSum(r, "SUMX2PY2", (x, y) -> x * x + y * y);
    pairSum(r, "SUMXMY2", (x, y) -> (x - y) * (x - y));
    r.raw(
        "SUMIF",
        2,
        3,
        (args, context) ->
            oneCriterion(
                args,
                context,
                (a, c) -> {
                  Value.Grid criteriaRange = grid(a[0]);
                  Value.Grid sumRange =
                      a.length > 2 && !(a[2] instanceof Value.Blank)
                          ? resized(a[2], criteriaRange)
                          : criteriaRange;
                  List<Double> matched = matching(sumRange, new Value[] {criteriaRange, a[1]});
                  return num(matched.stream().mapToDouble(Double::doubleValue).sum());
                }));
    r.raw(
        "SUMIFS",
        3,
        255,
        (args, context) ->
            forCriteria(
                args,
                2,
                context,
                (a, c) -> {
                  List<Double> matched = matching(grid(a[0]), Arrays.copyOfRange(a, 1, a.length));
                  return num(matched.stream().mapToDouble(Double::doubleValue).sum());
                }));
    r.raw(
        "AVERAGEIF",
        2,
        3,
        (args, context) ->
            oneCriterion(
                args,
                context,
                (a, c) -> {
                  Value.Grid criteriaRange = grid(a[0]);
                  Value.Grid avgRange =
                      a.length > 2 && !(a[2] instanceof Value.Blank)
                          ? resized(a[2], criteriaRange)
                          : criteriaRange;
                  return num(meanOf(matching(avgRange, new Value[] {criteriaRange, a[1]})));
                }));
    r.raw(
        "AVERAGEIFS",
        3,
        255,
        (args, context) ->
            forCriteria(
                args,
                2,
                context,
                (a, c) -> num(meanOf(matching(grid(a[0]), Arrays.copyOfRange(a, 1, a.length))))));
  }

  private static void pairSum(Registry r, String name, DoubleBinaryOperator term) {
    r.array(
        name,
        2,
        2,
        (a, c) -> {
          Value.Grid x = gridOrSingle(a[0]);
          Value.Grid y = gridOrSingle(a[1]);
          int count = x.rows() * x.columns();
          if (count != y.rows() * y.columns()) {
            throw error(ExcelError.NA);
          }
          // The shapes may differ: elements pair up in reading order.
          double sum = 0;
          int pairs = 0;
          for (int i = 0; i < count; i++) {
            Value p = x.get(i / x.columns(), i % x.columns());
            Value q = y.get(i / y.columns(), i % y.columns());
            if (p instanceof Value.Err e) {
              throw error(e.error());
            }
            if (q instanceof Value.Err e) {
              throw error(e.error());
            }
            if (p instanceof Value.Num m && q instanceof Value.Num o) {
              sum += term.applyAsDouble(m.value(), o.value());
              pairs++;
            }
          }
          if (pairs == 0) {
            throw error(ExcelError.DIV0);
          }
          return num(sum);
        });
  }

  private static Value.Grid gridOrSingle(Value value) {
    return value instanceof Value.Grid g ? g : new ArrayGrid(1, 1, new Value[] {value});
  }

  /** Treats the argument at index 1 as the only criterion. */
  private static Value oneCriterion(Value[] args, FunctionContext context, FunctionDef.Body body) {
    Value[] a = args.clone();
    if (a[1] instanceof RangeValue && !context.arrayMode()) {
      a[1] = Coercion.intersect(a[1], context);
    } else if (a[1] instanceof Value.Grid g) {
      return eachCriterion(a, 1, g, context, (x, c) -> oneCriterion(x, c, body));
    }
    return body.apply(a, context);
  }

  /**
   * Prepares criteria at every second index from {@code first}. A range intersects with the cell,
   * as any single value does. An array constant, or any array in an array formula, gives one result
   * for each element.
   */
  private static Value forCriteria(
      Value[] args, int first, FunctionContext context, FunctionDef.Body body) {
    Value[] a = args.clone();
    for (int i = first; i < a.length; i += 2) {
      if (a[i] instanceof RangeValue && !context.arrayMode()) {
        a[i] = Coercion.intersect(a[i], context);
      } else if (a[i] instanceof Value.Grid g) {
        return eachCriterion(a, i, g, context, (x, c) -> forCriteria(x, first, c, body));
      }
    }
    return body.apply(a, context);
  }

  private static Value eachCriterion(
      Value[] a, int index, Value.Grid criteria, FunctionContext context, FunctionDef.Body next) {
    int rows = criteria.usedRows();
    int columns = criteria.usedColumns();
    Value[] results = new Value[rows * columns];
    for (int r = 0; r < rows; r++) {
      for (int cl = 0; cl < columns; cl++) {
        Value[] one = a.clone();
        one[index] = criteria.get(r, cl);
        results[r * columns + cl] = next.apply(one, context);
      }
    }
    return new ArrayGrid(rows, columns, results);
  }

  private static double meanOf(List<Double> values) {
    return mean(values.stream().mapToDouble(Double::doubleValue).toArray());
  }

  private static Value.Grid grid(Value value) {
    if (value instanceof Value.Grid g) {
      return g;
    }
    if (value instanceof Value.Err e) {
      throw error(e.error());
    }
    throw error(ExcelError.VALUE);
  }

  /** Excel takes only the top-left corner of the sum range, and the shape of the criteria range. */
  private static Value.Grid resized(Value target, Value.Grid shape) {
    if (target instanceof RangeValue range) {
      return new RangeValue(
          range.source(),
          range.sheet(),
          range.firstColumn(),
          range.firstRow(),
          range.firstColumn() + shape.columns() - 1,
          range.firstRow() + shape.rows() - 1);
    }
    return grid(target);
  }

  /** The numbers of {@code valueRange} where every (range, criterion) pair matches. */
  private static List<Double> matching(Value.Grid valueRange, Value[] pairs) {
    if (pairs.length % 2 != 0) {
      throw error(ExcelError.VALUE);
    }
    List<Double> matched = new ArrayList<>();
    forEachMatch(
        pairs,
        (r, cl) -> {
          Value v = valueRange.get(r, cl);
          if (v instanceof Value.Err e) {
            throw error(e.error());
          }
          if (v instanceof Value.Num number) {
            matched.add(number.value());
          }
        });
    return matched;
  }

  private static double countIfs(Value[] pairs) {
    if (pairs.length % 2 != 0) {
      throw error(ExcelError.VALUE);
    }
    int[] count = {0};
    forEachMatch(pairs, (r, cl) -> count[0]++);
    return count[0] + blankMatches(pairs);
  }

  /** Blank cells beyond a range's used part still match a criterion such as {@code ""}. */
  private static double blankMatches(Value[] pairs) {
    double beyond = Double.MAX_VALUE;
    for (int i = 0; i < pairs.length; i += 2) {
      Value.Grid g = grid(pairs[i]);
      double extra = (double) g.rows() * g.columns() - (double) g.usedRows() * g.usedColumns();
      if (extra == 0 || !Criteria.of(pairs[i + 1]).test(Value.BLANK)) {
        return 0;
      }
      beyond = Math.min(beyond, extra);
    }
    return beyond;
  }

  private interface MatchAction {
    void accept(int row, int column);
  }

  private static void forEachMatch(Value[] pairs, MatchAction action) {
    Value.Grid first = grid(pairs[0]);
    List<Predicate<Value>> tests = new ArrayList<>();
    List<Value.Grid> ranges = new ArrayList<>();
    for (int i = 0; i < pairs.length; i += 2) {
      Value.Grid g = grid(pairs[i]);
      if (g.rows() != first.rows() || g.columns() != first.columns()) {
        throw error(ExcelError.VALUE);
      }
      ranges.add(g);
      tests.add(Criteria.of(pairs[i + 1]));
    }
    int rows = first.usedRows();
    int columns = first.usedColumns();
    for (Value.Grid g : ranges) {
      rows = Math.max(rows, g.usedRows());
      columns = Math.max(columns, g.usedColumns());
    }
    for (int r = 0; r < rows; r++) {
      for (int cl = 0; cl < columns; cl++) {
        boolean all = true;
        for (int i = 0; i < tests.size() && all; i++) {
          all = tests.get(i).test(ranges.get(i).get(r, cl));
        }
        if (all) {
          action.accept(r, cl);
        }
      }
    }
  }

  private static double count(Value[] args) {
    int[] count = {0};
    for (Value arg : args) {
      if (arg instanceof Value.Grid) {
        Args.each(
            arg,
            v -> {
              if (v instanceof Value.Num) {
                count[0]++;
              }
            });
      } else if (arg instanceof Value.Num || arg instanceof Value.Bool) {
        count[0]++;
      } else if (arg instanceof Value.Text t
          && io.github.joshblazer.cellerity.core.Coercion.parseNumber(t.value()).isPresent()) {
        count[0]++;
      }
    }
    return count[0];
  }

  private static double countA(Value[] args) {
    int[] count = {0};
    for (Value arg : args) {
      Args.each(
          arg,
          v -> {
            if (!(v instanceof Value.Blank)) {
              count[0]++;
            }
          });
    }
    return count[0];
  }

  private static double mean(double[] v) {
    if (v.length == 0) {
      throw error(ExcelError.DIV0);
    }
    return Arrays.stream(v).sum() / v.length;
  }

  private static double max(double[] v) {
    return v.length == 0 ? 0 : Arrays.stream(v).max().orElse(0);
  }

  private static double min(double[] v) {
    return v.length == 0 ? 0 : Arrays.stream(v).min().orElse(0);
  }

  private static double[] sorted(double[] v) {
    double[] copy = v.clone();
    Arrays.sort(copy);
    return copy;
  }

  private static int rank(double k, int length) {
    int rank = (int) Math.ceil(k);
    if (length == 0 || rank < 1 || rank > length) {
      throw error(ExcelError.NUM);
    }
    return rank;
  }

  private static double percentile(double[] values, double p) {
    if (values.length == 0 || p < 0 || p > 1) {
      throw error(ExcelError.NUM);
    }
    double[] v = sorted(values);
    double position = p * (v.length - 1);
    int low = (int) Math.floor(position);
    int high = Math.min(low + 1, v.length - 1);
    return v[low] + (position - low) * (v[high] - v[low]);
  }

  private static double variance(double[] v, int lost) {
    if (v.length <= lost) {
      throw error(ExcelError.DIV0);
    }
    return sxx(v) / (v.length - lost);
  }

  private static double sxx(double[] v) {
    double m = mean(v);
    double sum = 0;
    for (double x : v) {
      sum += (x - m) * (x - m);
    }
    return sum;
  }

  private static double covariance(double[] x, double[] y) {
    double mx = mean(x);
    double my = mean(y);
    double sum = 0;
    for (int i = 0; i < x.length; i++) {
      sum += (x[i] - mx) * (y[i] - my);
    }
    return sum;
  }

  private static double division(double x, double y) {
    if (y == 0) {
      throw error(ExcelError.DIV0);
    }
    return x / y;
  }

  /** Paired numbers from two arrays. A pair with anything but numbers is dropped. */
  private static double[][] pairs(Value a, Value b) {
    Value.Grid x = grid(a);
    Value.Grid y = grid(b);
    if ((double) x.rows() * x.columns() != (double) y.rows() * y.columns()) {
      throw error(ExcelError.NA);
    }
    List<double[]> pairs = new ArrayList<>();
    int rows = Math.max(x.usedRows(), y.usedRows());
    int columns = Math.max(x.usedColumns(), y.usedColumns());
    for (int r = 0; r < rows; r++) {
      for (int cl = 0; cl < columns; cl++) {
        Value p = x.get(r, cl);
        Value q = y.get(r, cl);
        if (p instanceof Value.Err e) {
          throw error(e.error());
        }
        if (q instanceof Value.Err e) {
          throw error(e.error());
        }
        if (p instanceof Value.Num m && q instanceof Value.Num o) {
          pairs.add(new double[] {m.value(), o.value()});
        }
      }
    }
    if (pairs.isEmpty()) {
      throw error(ExcelError.DIV0);
    }
    double[][] out = new double[2][pairs.size()];
    for (int i = 0; i < pairs.size(); i++) {
      out[0][i] = pairs.get(i)[0];
      out[1][i] = pairs.get(i)[1];
    }
    return out;
  }

  /**
   * Dispatches on the function number. Known limit: nested subtotals and hidden rows still count.
   */
  private static Value subtotal(Value[] a) {
    int code = (int) n(a, 0);
    code = code > 100 ? code - 100 : code;
    Value[] rest = Arrays.copyOfRange(a, 1, a.length);
    String name =
        switch (code) {
          case 1 -> "AVERAGE";
          case 2 -> "COUNT";
          case 3 -> "COUNTA";
          case 4 -> "MAX";
          case 5 -> "MIN";
          case 6 -> "PRODUCT";
          case 7 -> "STDEV";
          case 8 -> "STDEVP";
          case 9 -> "SUM";
          case 10 -> "VAR";
          case 11 -> "VARP";
          default -> throw error(ExcelError.VALUE);
        };
    return Functions.get(name).orElseThrow().body().apply(rest, null);
  }

  /** Cumulative normal, from West's double-precision form of Hart's algorithm. */
  private static double normalCdf(double z) {
    double x = Math.abs(z);
    double cumulative;
    if (x > 37) {
      cumulative = 0;
    } else {
      double e = Math.exp(-x * x / 2);
      if (x < 7.07106781186547) {
        double b = 3.52624965998911e-02 * x + 0.700383064443688;
        b = b * x + 6.37396220353165;
        b = b * x + 33.912866078383;
        b = b * x + 112.079291497871;
        b = b * x + 221.213596169931;
        b = b * x + 220.206867912376;
        cumulative = e * b;
        b = 8.83883476483184e-02 * x + 1.75566716318264;
        b = b * x + 16.064177579207;
        b = b * x + 86.7807322029461;
        b = b * x + 296.564248779674;
        b = b * x + 637.333633378831;
        b = b * x + 793.826512519948;
        b = b * x + 440.413735824752;
        cumulative = cumulative / b;
      } else {
        double b = x + 0.65;
        b = x + 4 / b;
        b = x + 3 / b;
        b = x + 2 / b;
        b = x + 1 / b;
        cumulative = e / b / 2.506628274631;
      }
    }
    return z > 0 ? 1 - cumulative : cumulative;
  }

  /** Acklam's rational approximation, polished with one Halley step. */
  private static double normalInverse(double p) {
    double[] a = {
      -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
      1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00
    };
    double[] b = {
      -5.447609879822406e+01,
      1.615858368580409e+02,
      -1.556989798598866e+02,
      6.680131188771972e+01,
      -1.328068155288572e+01
    };
    double[] d = {
      7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00
    };
    double[] e = {
      -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
      -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00
    };
    double low = 0.02425;
    double x;
    if (p < low || p > 1 - low) {
      double q = Math.sqrt(-2 * Math.log(p < low ? p : 1 - p));
      x =
          (((((e[0] * q + e[1]) * q + e[2]) * q + e[3]) * q + e[4]) * q + e[5])
              / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
      x = p < low ? x : -x;
    } else {
      double q = p - 0.5;
      double t = q * q;
      x =
          (((((a[0] * t + a[1]) * t + a[2]) * t + a[3]) * t + a[4]) * t + a[5])
              * q
              / (((((b[0] * t + b[1]) * t + b[2]) * t + b[3]) * t + b[4]) * t + 1);
    }
    double error = normalCdf(x) - p;
    double u = error * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
    return x - u / (1 + x * u / 2);
  }
}
