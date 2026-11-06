package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.n;
import static io.github.joshblazer.cellerity.core.functions.Args.num;
import static io.github.joshblazer.cellerity.core.functions.Args.s;
import static io.github.joshblazer.cellerity.core.functions.Args.text;

import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TextFunctions {
  private TextFunctions() {}

  static void register(Registry r) {
    r.scalar(
        "LEFT",
        1,
        2,
        (a, c) -> {
          String value = s(a, 0);
          return text(value.substring(0, Math.min(value.length(), count(a, 1))));
        });
    r.scalar(
        "RIGHT",
        1,
        2,
        (a, c) -> {
          String value = s(a, 0);
          return text(value.substring(value.length() - Math.min(value.length(), count(a, 1))));
        });
    r.scalar(
        "MID",
        3,
        3,
        (a, c) -> {
          String value = s(a, 0);
          double start = Math.floor(n(a, 1));
          double length = Math.floor(n(a, 2));
          if (start < 1 || length < 0) {
            throw error(ExcelError.VALUE);
          }
          if (start > value.length()) {
            return text("");
          }
          int from = (int) start - 1;
          return text(value.substring(from, (int) Math.min(value.length(), from + length)));
        });
    r.scalar("LEN", 1, 1, (a, c) -> num(s(a, 0).length()));
    r.scalar(
        "FIND",
        2,
        3,
        (a, c) -> {
          String within = s(a, 1);
          int start = start(a, within);
          int at = within.indexOf(s(a, 0), start);
          if (at < 0) {
            throw error(ExcelError.VALUE);
          }
          return num(at + 1.0);
        });
    r.scalar(
        "SEARCH",
        2,
        3,
        (a, c) -> {
          String within = s(a, 1);
          int start = start(a, within);
          String pattern = s(a, 0);
          Matcher m =
              Pattern.compile(
                      Criteria.wildcard(pattern).pattern().replace(".*", ".*?"),
                      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                  .matcher(within);
          if (!m.find(start)) {
            throw error(ExcelError.VALUE);
          }
          return num(m.start() + 1.0);
        });
    r.scalar("UPPER", 1, 1, (a, c) -> text(s(a, 0).toUpperCase(Locale.ROOT)));
    r.scalar("LOWER", 1, 1, (a, c) -> text(s(a, 0).toLowerCase(Locale.ROOT)));
    r.scalar(
        "PROPER",
        1,
        1,
        (a, c) -> {
          StringBuilder out = new StringBuilder();
          boolean startOfWord = true;
          for (char ch : s(a, 0).toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            startOfWord = !Character.isLetter(ch);
          }
          return text(out.toString());
        });
    r.scalar("TRIM", 1, 1, (a, c) -> text(s(a, 0).replaceAll(" +", " ").replaceAll("^ | $", "")));
    r.scalar("CLEAN", 1, 1, (a, c) -> text(s(a, 0).replaceAll("[\\x00-\\x1F]", "")));
    r.raw(
        "CONCATENATE",
        1,
        255,
        (a, c) -> {
          StringBuilder out = new StringBuilder();
          for (Value arg : a) {
            out.append(Coercion.toText(Coercion.intersect(arg, c)));
          }
          return text(out.toString());
        });
    r.raw(
        "CONCAT",
        1,
        255,
        (a, c) -> {
          StringBuilder out = new StringBuilder();
          for (Value arg : a) {
            Args.each(arg, v -> out.append(Coercion.toText(v)));
          }
          return text(out.toString());
        });
    r.raw(
        "TEXTJOIN",
        3,
        255,
        (a, c) -> {
          String delimiter = Coercion.toText(a[0]);
          boolean skipEmpty = Coercion.toBool(a[1]);
          List<String> pieces = new ArrayList<>();
          for (int i = 2; i < a.length; i++) {
            Args.each(
                a[i],
                v -> {
                  String piece = Coercion.toText(v);
                  if (!skipEmpty || !piece.isEmpty()) {
                    pieces.add(piece);
                  }
                });
          }
          return text(String.join(delimiter, pieces));
        });
    r.scalar(
        "REPLACE",
        4,
        4,
        (a, c) -> {
          String value = s(a, 0);
          double start = Math.floor(n(a, 1));
          double length = Math.floor(n(a, 2));
          if (start < 1 || length < 0) {
            throw error(ExcelError.VALUE);
          }
          int from = (int) Math.min(start - 1, value.length());
          int to = (int) Math.min(from + length, value.length());
          return text(value.substring(0, from) + s(a, 3) + value.substring(to));
        });
    r.scalar(
        "SUBSTITUTE",
        3,
        4,
        (a, c) -> {
          String value = s(a, 0);
          String old = s(a, 1);
          String replacement = s(a, 2);
          if (old.isEmpty()) {
            return text(value);
          }
          if (a.length < 4) {
            return text(value.replace(old, replacement));
          }
          double occurrence = Math.floor(n(a, 3));
          if (occurrence < 1) {
            throw error(ExcelError.VALUE);
          }
          int at = -1;
          for (int i = 0; i < occurrence; i++) {
            at = value.indexOf(old, at + (i == 0 ? 1 : old.length()));
            if (at < 0) {
              return text(value);
            }
          }
          return text(value.substring(0, at) + replacement + value.substring(at + old.length()));
        });
    r.scalar(
        "REPT",
        2,
        2,
        (a, c) -> {
          double times = Math.floor(n(a, 1));
          String value = s(a, 0);
          if (times < 0 || times * value.length() > 32_767) {
            throw error(ExcelError.VALUE);
          }
          return text(value.repeat((int) times));
        });
    r.scalar(
        "VALUE",
        1,
        1,
        (a, c) -> {
          OptionalDouble parsed =
              a[0] instanceof Value.Num x
                  ? OptionalDouble.of(x.value())
                  : Coercion.parseNumber(s(a, 0));
          if (parsed.isEmpty()) {
            throw error(ExcelError.VALUE);
          }
          return num(parsed.getAsDouble());
        });
    r.scalar("EXACT", 2, 2, (a, c) -> Args.bool(s(a, 0).equals(s(a, 1))));
    r.scalar(
        "CHAR",
        1,
        1,
        (a, c) -> {
          double code = Math.floor(n(a, 0));
          if (code < 1 || code > 255) {
            throw error(ExcelError.VALUE);
          }
          return text(String.valueOf((char) code));
        });
    r.scalar(
        "CODE",
        1,
        1,
        (a, c) -> {
          String value = s(a, 0);
          if (value.isEmpty()) {
            throw error(ExcelError.VALUE);
          }
          return num(value.charAt(0));
        });
    r.scalar(
        "TEXT",
        2,
        2,
        (a, c) -> {
          String code = s(a, 1);
          if (a[0] instanceof Value.Text t) {
            OptionalDouble number = Coercion.parseNumber(t.value());
            return number.isPresent()
                ? text(NumberFormatter.format(number.getAsDouble(), code, c.dates()))
                : text(NumberFormatter.formatText(t.value(), code));
          }
          return text(NumberFormatter.format(n(a, 0), code, c.dates()));
        });
    r.scalar(
        "FIXED",
        1,
        3,
        (a, c) -> {
          double digits = n(a, 1, 2);
          boolean noCommas = a.length > 2 && Coercion.toBool(a[2]);
          double rounded = MathFunctions.round(n(a, 0), digits, java.math.RoundingMode.HALF_UP);
          String code =
              (noCommas ? "0" : "#,##0") + (digits > 0 ? "." + "0".repeat((int) digits) : "");
          return text(NumberFormatter.format(rounded, code, c.dates()));
        });
    r.scalar(
        "DOLLAR",
        1,
        2,
        (a, c) -> {
          double digits = n(a, 1, 2);
          double rounded = MathFunctions.round(n(a, 0), digits, java.math.RoundingMode.HALF_UP);
          String body = "$#,##0" + (digits > 0 ? "." + "0".repeat((int) digits) : "");
          return text(NumberFormatter.format(rounded, body + ";(" + body + ")", c.dates()));
        });
  }

  private static int count(Value[] a, int i) {
    double count = i < a.length ? Math.floor(n(a, i)) : 1;
    if (count < 0) {
      throw error(ExcelError.VALUE);
    }
    return (int) Math.min(count, Integer.MAX_VALUE);
  }

  private static int start(Value[] a, String within) {
    double start = a.length > 2 ? Math.floor(n(a, 2)) : 1;
    if (start < 1 || start > within.length() + 1) {
      throw error(ExcelError.VALUE);
    }
    return (int) start - 1;
  }
}
