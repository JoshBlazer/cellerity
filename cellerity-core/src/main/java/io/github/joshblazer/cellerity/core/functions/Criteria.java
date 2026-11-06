package io.github.joshblazer.cellerity.core.functions;

import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Operators;
import io.github.joshblazer.cellerity.core.Value;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** The criteria language of {@code SUMIF}, {@code COUNTIF}, and {@code MATCH}'s wildcards. */
final class Criteria {
  private Criteria() {}

  /** A test for cells, from a criterion value. */
  static Predicate<Value> of(Value criterion) {
    Value c = Coercion.first(criterion);
    return switch (c) {
      case Value.Num n -> v -> equalsNumber(v, n.value());
      case Value.Bool b -> v -> v instanceof Value.Bool x && x.value() == b.value();
      case Value.Err e -> v -> v instanceof Value.Err x && x.error() == e.error();
      case Value.Blank blank -> v -> equalsNumber(v, 0);
      case Value.Text t -> ofText(t.value());
      case Value.Grid g -> v -> false;
    };
  }

  private static Predicate<Value> ofText(String text) {
    String op = "=";
    String rest = text;
    for (String candidate : new String[] {">=", "<=", "<>", ">", "<", "="}) {
      if (text.startsWith(candidate)) {
        op = candidate;
        rest = text.substring(candidate.length());
        break;
      }
    }
    String operator = op;
    String operand = rest;
    OptionalDouble number = Coercion.parseNumber(operand);
    boolean bare = op.equals("=") && text.equals(rest);
    switch (op) {
      case "=", "<>" -> {
        Predicate<Value> equal = equalTest(operand, number, bare);
        return op.equals("=") ? equal : equal.negate();
      }
      default -> {
        return v -> ordered(v, operator, operand, number);
      }
    }
  }

  private static Predicate<Value> equalTest(String operand, OptionalDouble number, boolean bare) {
    if (operand.isEmpty()) {
      // "" matches blanks and empty text. "=" matches only true blanks.
      return bare
          ? v -> v instanceof Value.Blank || (v instanceof Value.Text t && t.value().isEmpty())
          : v -> v instanceof Value.Blank;
    }
    if (number.isPresent()) {
      double target = number.getAsDouble();
      return v ->
          equalsNumber(v, target)
              || (v instanceof Value.Text t
                  && Coercion.parseNumber(t.value()).orElse(Double.NaN) == target);
    }
    if (operand.equalsIgnoreCase("TRUE") || operand.equalsIgnoreCase("FALSE")) {
      boolean target = operand.equalsIgnoreCase("TRUE");
      return v -> v instanceof Value.Bool b && b.value() == target;
    }
    var error = ExcelError.fromCode(operand.toUpperCase(Locale.ROOT));
    if (error.isPresent()) {
      return v -> v instanceof Value.Err e && e.error() == error.get();
    }
    Pattern pattern = wildcard(operand);
    return v -> v instanceof Value.Text t && pattern.matcher(t.value()).matches();
  }

  private static boolean ordered(Value v, String op, String operand, OptionalDouble number) {
    int order;
    if (number.isPresent()) {
      if (!(v instanceof Value.Num n)) {
        return false;
      }
      order = Double.compare(n.value(), number.getAsDouble());
    } else {
      if (!(v instanceof Value.Text)) {
        return false;
      }
      order = Operators.compare(v, new Value.Text(operand));
    }
    return switch (op) {
      case ">" -> order > 0;
      case ">=" -> order >= 0;
      case "<" -> order < 0;
      default -> order <= 0;
    };
  }

  private static boolean equalsNumber(Value v, double target) {
    return v instanceof Value.Num n && n.value() == target;
  }

  /** Compiles a pattern with {@code *}, {@code ?}, and {@code ~} escapes, ignoring case. */
  static Pattern wildcard(String text) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '~' && i + 1 < text.length()) {
        regex.append(Pattern.quote(String.valueOf(text.charAt(++i))));
      } else if (ch == '*') {
        regex.append(".*");
      } else if (ch == '?') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(ch)));
      }
    }
    return Pattern.compile(
        regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  }

  static boolean hasWildcard(String text) {
    return text.indexOf('*') >= 0 || text.indexOf('?') >= 0 || text.indexOf('~') >= 0;
  }
}
