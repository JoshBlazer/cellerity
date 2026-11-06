package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.core.functions.Functions;
import io.github.joshblazer.cellerity.formula.Expr;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Describes a mismatch without its content, so it can go in public logs and committed results
 * (ADR-0013). The text holds a formula's shape and how the two values differ. It never holds a
 * literal, a cell address, a sheet name, a defined name, or a value, and names only functions we
 * implement.
 */
final class MismatchPattern {
  private static final int MAX_DEPTH = 4;
  private static final int MAX_ARGS = 6;

  private MismatchPattern() {}

  static String of(Expr expr, Value expected, Value actual) {
    return shape(expr, 0) + " | " + difference(expected, actual);
  }

  static String shape(Expr expr, int depth) {
    if (depth > MAX_DEPTH) {
      return "..";
    }
    int next = depth + 1;
    return switch (expr) {
      case Expr.Num n -> "NUM";
      case Expr.Str s -> "STR";
      case Expr.Bool b -> "BOOL";
      case Expr.Err e -> e.error().code();
      case Expr.Missing m -> "";
      case Expr.Cell c -> c.scope().sheet().isPresent() ? "XREF" : "REF";
      case Expr.Area a -> a.scope().sheet().isPresent() ? "XRANGE" : "RANGE";
      case Expr.BrokenRef r -> "BROKENREF";
      case Expr.Name n -> "NAME";
      case Expr.Structured s -> "STRUCTURED";
      case Expr.Invalid i -> "INVALID";
      case Expr.ArrayLit a -> "ARRAY";
      case Expr.Paren p -> "(" + shape(p.inner(), next) + ")";
      case Expr.Percent p -> shape(p.operand(), next) + "%";
      case Expr.Unary u -> u.op().symbol() + shape(u.operand(), next);
      case Expr.Binary b -> shape(b.left(), next) + b.op().symbol() + shape(b.right(), next);
      case Expr.Call c -> functionName(c) + "(" + args(c.args(), next) + ")";
    };
  }

  private static String functionName(Expr.Call call) {
    String name = call.canonicalName();
    return Functions.supported(name) ? name : "OTHERFN";
  }

  private static String args(List<Expr> args, int depth) {
    String shown =
        args.stream()
            .limit(MAX_ARGS)
            .map(arg -> shape(arg, depth))
            .collect(Collectors.joining(","));
    return args.size() > MAX_ARGS ? shown + ",.." : shown;
  }

  static String difference(Value expected, Value actual) {
    if (expected instanceof Value.Num e && actual instanceof Value.Num a) {
      return "number vs number, " + numberGap(e.value(), a.value());
    }
    if (expected instanceof Value.Text e && actual instanceof Value.Text a) {
      return "text vs text, " + textGap(e.value(), a.value());
    }
    return kind(expected) + " vs " + kind(actual);
  }

  private static String numberGap(double expected, double actual) {
    if (expected == 0 || actual == 0) {
      return "one is zero";
    }
    if (Math.signum(expected) != Math.signum(actual)) {
      return "opposite signs";
    }
    double gap = Math.abs(expected - actual);
    double relative = gap / Math.max(Math.abs(expected), Math.abs(actual));
    if (relative < 1e-9) {
      return "gap below 1e-9, " + absolute(gap);
    }
    if (relative < 1e-6) {
      return "gap below 1e-6, " + absolute(gap);
    }
    if (relative < 1e-3) {
      return "gap below 1e-3, " + absolute(gap);
    }
    return relative < 0.5 ? "gap below 50%" : "gap above 50%";
  }

  /** Excel states its solvers' accuracy in absolute terms, so this is the evidence for them. */
  private static String absolute(double gap) {
    for (int exponent = -10; exponent <= -5; exponent++) {
      if (gap < Math.pow(10, exponent)) {
        return "absolute below 1e" + exponent;
      }
    }
    return "absolute above 1e-5";
  }

  private static String textGap(String expected, String actual) {
    if (expected.isEmpty() || actual.isEmpty()) {
      return "one is empty";
    }
    if (expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT))) {
      return "differ by case";
    }
    return expected.strip().equals(actual.strip()) ? "differ by outer spaces" : "differ";
  }

  private static String kind(Value value) {
    return switch (value) {
      case Value.Num n -> "number";
      case Value.Text t -> "text";
      case Value.Bool b -> "boolean";
      case Value.Err e -> e.error().code();
      case Value.Blank b -> "blank";
      case Value.Grid g -> "array";
    };
  }
}
