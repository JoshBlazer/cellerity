package io.github.joshblazer.cellerity.core.functions;

import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.Dates;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats numbers for {@code TEXT}. Covers common number and date codes in the en-US locale.
 * Fractions and conditional sections are not supported and give {@code #VALUE!} rather than a wrong
 * string.
 */
final class NumberFormatter {
  private static final String[] MONTHS = {
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December"
  };
  private static final String[] DAYS = {
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
  };

  private NumberFormatter() {}

  static String format(double value, String code, DateSystem system) {
    List<String> sections = split(code);
    if (sections.size() > 4) {
      throw new FunctionError(ExcelError.VALUE);
    }
    String section;
    boolean explicitSign = false;
    if (sections.size() >= 3 && value == 0) {
      section = sections.get(2);
    } else if (sections.size() >= 2 && value < 0) {
      section = sections.get(1);
      explicitSign = true;
    } else {
      section = sections.get(0);
    }
    String cleaned = stripBrackets(section);
    if (cleaned.equalsIgnoreCase("General") || (cleaned.isEmpty() && code.isEmpty())) {
      return Coercion.numberToText(explicitSign ? Math.abs(value) : value);
    }
    if (isDateCode(cleaned)) {
      return date(value, cleaned, system);
    }
    double magnitude = explicitSign ? Math.abs(value) : value;
    return number(magnitude, cleaned);
  }

  /** Formats text with the {@code @} section, or returns it unchanged. */
  static String formatText(String value, String code) {
    List<String> sections = split(code);
    String section = sections.size() == 4 ? sections.get(3) : sections.get(0);
    if (section.indexOf('@') < 0) {
      return value;
    }
    StringBuilder out = new StringBuilder();
    forEachPart(
        section,
        (literal, ch) -> {
          if (!literal && ch == '@') {
            out.append(value);
          } else {
            out.append(ch);
          }
        });
    return out.toString();
  }

  private interface PartAction {
    void accept(boolean literal, char ch);
  }

  /** Walks a section, resolving quotes and backslash escapes. */
  private static void forEachPart(String section, PartAction action) {
    for (int i = 0; i < section.length(); i++) {
      char ch = section.charAt(i);
      if (ch == '"') {
        int end = section.indexOf('"', i + 1);
        end = end < 0 ? section.length() : end;
        for (int j = i + 1; j < end; j++) {
          action.accept(true, section.charAt(j));
        }
        i = end;
      } else if (ch == '\\' && i + 1 < section.length()) {
        action.accept(true, section.charAt(++i));
      } else if (ch == '_' && i + 1 < section.length()) {
        action.accept(true, ' ');
        i++;
      } else if (ch == '*' && i + 1 < section.length()) {
        i++;
      } else {
        action.accept(false, ch);
      }
    }
  }

  private static List<String> split(String code) {
    List<String> sections = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < code.length(); i++) {
      char ch = code.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
      }
      if (ch == '\\' && i + 1 < code.length()) {
        current.append(ch).append(code.charAt(++i));
      } else if (ch == ';' && !quoted) {
        sections.add(current.toString());
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    sections.add(current.toString());
    return sections;
  }

  /** Removes colors and locale tags. Conditions and elapsed-time codes are not supported. */
  private static String stripBrackets(String section) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < section.length()) {
      char ch = section.charAt(i);
      if (ch == '"') {
        int end = section.indexOf('"', i + 1);
        end = end < 0 ? section.length() - 1 : end;
        out.append(section, i, end + 1);
        i = end + 1;
      } else if (ch == '[') {
        int end = section.indexOf(']', i);
        if (end < 0) {
          throw new FunctionError(ExcelError.VALUE);
        }
        String tag = section.substring(i + 1, end);
        if (tag.matches("[<>=].*") || tag.matches("(?i)h+|m+|s+")) {
          throw new FunctionError(ExcelError.VALUE);
        }
        i = end + 1;
      } else {
        out.append(ch);
        i++;
      }
    }
    return out.toString();
  }

  private static boolean isDateCode(String section) {
    boolean[] date = {false};
    forEachPart(
        section,
        (literal, ch) -> {
          if (!literal && "yYmMdDhHsS".indexOf(ch) >= 0) {
            date[0] = true;
          }
        });
    return date[0] && !section.matches("(?i)^[^a-z]*e[+-].*");
  }

  private static String date(double value, String section, DateSystem system) {
    if (value < 0) {
      throw new FunctionError(ExcelError.VALUE);
    }
    double whole = Math.floor(value);
    long seconds = Math.round((value - whole) * 86_400);
    if (seconds == 86_400) {
      seconds = 0;
      whole += 1;
    }
    Dates.Ymd ymd = Dates.ymd(whole, system);
    int weekday = Dates.dayOfWeekIndex(whole, system);
    long hour = seconds / 3600;
    long minute = seconds / 60 % 60;
    long second = seconds % 60;
    boolean twelveHour =
        section.toUpperCase(Locale.ROOT).contains("AM/PM")
            || section.toUpperCase(Locale.ROOT).contains("A/P");
    List<String[]> tokens = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    char[] last = {0};
    int[] run = {0};
    Runnable flush =
        () -> {
          if (last[0] != 0) {
            tokens.add(new String[] {String.valueOf(last[0]), String.valueOf(run[0])});
            last[0] = 0;
            run[0] = 0;
          }
        };
    String upper = section.toUpperCase(Locale.ROOT);
    int i = 0;
    while (i < section.length()) {
      char ch = section.charAt(i);
      if (upper.startsWith("AM/PM", i) || upper.startsWith("A/P", i)) {
        flush.run();
        int length = upper.startsWith("AM/PM", i) ? 5 : 3;
        tokens.add(new String[] {"P", String.valueOf(length)});
        i += length;
      } else if ("yYmMdDhHsS".indexOf(ch) >= 0) {
        char lower = Character.toLowerCase(ch);
        if (last[0] != lower) {
          flush.run();
          last[0] = lower;
        }
        run[0]++;
        i++;
      } else {
        flush.run();
        int end = i;
        literal.setLength(0);
        while (end < section.length()
            && "yYmMdDhHsS".indexOf(section.charAt(end)) < 0
            && !upper.startsWith("AM/PM", end)
            && !upper.startsWith("A/P", end)) {
          char x = section.charAt(end);
          if (x == '"') {
            int close = section.indexOf('"', end + 1);
            close = close < 0 ? section.length() : close;
            literal.append(section, end + 1, close);
            end = close + 1;
          } else if (x == '\\' && end + 1 < section.length()) {
            literal.append(section.charAt(end + 1));
            end += 2;
          } else {
            literal.append(x);
            end++;
          }
        }
        tokens.add(new String[] {"L", literal.toString()});
        i = Math.min(end, section.length());
      }
    }
    flush.run();
    StringBuilder out = new StringBuilder();
    for (int t = 0; t < tokens.size(); t++) {
      String kind = tokens.get(t)[0];
      if (kind.equals("L")) {
        out.append(tokens.get(t)[1]);
        continue;
      }
      if (kind.equals("P")) {
        boolean full = tokens.get(t)[1].equals("5");
        out.append(hour < 12 ? (full ? "AM" : "A") : (full ? "PM" : "P"));
        continue;
      }
      int length = Integer.parseInt(tokens.get(t)[1]);
      switch (kind) {
        case "y" ->
            out.append(
                length <= 2
                    ? String.format(Locale.ROOT, "%02d", ymd.year() % 100)
                    : String.valueOf(ymd.year()));
        case "d" -> {
          if (length == 1) {
            out.append(ymd.day());
          } else if (length == 2) {
            out.append(String.format(Locale.ROOT, "%02d", ymd.day()));
          } else {
            out.append(length == 3 ? DAYS[weekday].substring(0, 3) : DAYS[weekday]);
          }
        }
        case "h" -> {
          long shown = twelveHour ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
          out.append(
              length >= 2 ? String.format(Locale.ROOT, "%02d", shown) : String.valueOf(shown));
        }
        case "s" ->
            out.append(
                length >= 2 ? String.format(Locale.ROOT, "%02d", second) : String.valueOf(second));
        default -> {
          if (isMinutes(tokens, t)) {
            out.append(
                length >= 2 ? String.format(Locale.ROOT, "%02d", minute) : String.valueOf(minute));
          } else {
            String name = ymd.month() >= 1 ? MONTHS[ymd.month() - 1] : "";
            switch (length) {
              case 1 -> out.append(ymd.month());
              case 2 -> out.append(String.format(Locale.ROOT, "%02d", ymd.month()));
              case 3 -> out.append(name, 0, 3);
              case 5 -> out.append(name.charAt(0));
              default -> out.append(name);
            }
          }
        }
      }
    }
    return out.toString();
  }

  /** {@code m} means minutes straight after an hour code or straight before a seconds code. */
  private static boolean isMinutes(List<String[]> tokens, int index) {
    for (int i = index - 1; i >= 0; i--) {
      String kind = tokens.get(i)[0];
      if (!kind.equals("L")) {
        if (kind.equals("h")) {
          return true;
        }
        break;
      }
    }
    for (int i = index + 1; i < tokens.size(); i++) {
      String kind = tokens.get(i)[0];
      if (!kind.equals("L")) {
        return kind.equals("s");
      }
    }
    return false;
  }

  private static String number(double input, String section) {
    List<Object> parts = new ArrayList<>();
    StringBuilder pending = new StringBuilder();
    forEachPart(
        section,
        (literal, ch) -> {
          if (!literal
              && (ch == '0' || ch == '#' || ch == '?' || ch == '.' || ch == ',' || ch == '%'
                  || ch == 'E' || ch == 'e' || ch == '/')) {
            if (pending.length() > 0) {
              parts.add(pending.toString());
              pending.setLength(0);
            }
            parts.add(Character.valueOf(ch));
          } else {
            pending.append(ch);
          }
        });
    if (pending.length() > 0) {
      parts.add(pending.toString());
    }
    int exponentAt = -1;
    int integerDigits = 0;
    int decimalDigits = 0;
    int percents = 0;
    boolean inDecimals = false;
    boolean grouping = false;
    int lastPlaceholder = -1;
    for (int i = 0; i < parts.size(); i++) {
      Object part = parts.get(i);
      if (!(part instanceof Character ch)) {
        continue;
      }
      if (ch == '/') {
        throw new FunctionError(ExcelError.VALUE);
      }
      if ((ch == 'E' || ch == 'e') && exponentAt < 0) {
        exponentAt = i;
      } else if (exponentAt < 0 && (ch == '0' || ch == '#' || ch == '?')) {
        lastPlaceholder = i;
        if (inDecimals) {
          decimalDigits++;
        } else {
          integerDigits++;
        }
      } else if (ch == '.') {
        inDecimals = true;
      } else if (ch == '%') {
        percents++;
      } else if (ch == ',' && exponentAt < 0 && !inDecimals) {
        if (i > 0
            && i + 1 < parts.size()
            && parts.get(i + 1) instanceof Character next
            && isPlaceholder(next)) {
          grouping = true;
        }
      }
    }
    double scaled = input * Math.pow(100, percents);
    int scaleCommas = 0;
    for (int i = lastPlaceholder + 1; i < parts.size(); i++) {
      if (parts.get(i) instanceof Character ch && ch == ',') {
        scaleCommas++;
      } else if (!(parts.get(i) instanceof Character ch && ch == '.')) {
        break;
      }
    }
    scaled /= Math.pow(1000, scaleCommas);
    boolean negative = scaled < 0;
    double magnitude = Math.abs(scaled);
    String exponentText = "";
    if (exponentAt >= 0) {
      int shown = Math.max(1, integerDigits);
      int exponent = magnitude == 0 ? 0 : (int) Math.floor(Math.log10(magnitude)) - (shown - 1);
      BigDecimal mantissa =
          new BigDecimal(Double.toString(magnitude))
              .movePointLeft(exponent)
              .setScale(decimalDigits, RoundingMode.HALF_UP);
      if (mantissa.precision() - mantissa.scale() > shown && magnitude != 0) {
        exponent += 1;
        mantissa =
            new BigDecimal(Double.toString(magnitude))
                .movePointLeft(exponent)
                .setScale(decimalDigits, RoundingMode.HALF_UP);
      }
      exponentText = exponentPart(parts, exponentAt, exponent);
      return render(parts, exponentAt, mantissa, integerDigits, decimalDigits, false, negative)
          + exponentText;
    }
    BigDecimal rounded =
        new BigDecimal(Double.toString(magnitude)).setScale(decimalDigits, RoundingMode.HALF_UP);
    return render(
        parts,
        parts.size(),
        rounded,
        integerDigits,
        decimalDigits,
        grouping,
        negative && rounded.signum() != 0);
  }

  private static boolean isPlaceholder(char ch) {
    return ch == '0' || ch == '#' || ch == '?';
  }

  private static String exponentPart(List<Object> parts, int at, int exponent) {
    StringBuilder out = new StringBuilder("E");
    int i = at + 1;
    boolean showPlus = false;
    if (i < parts.size()
        && parts.get(i) instanceof String s
        && !s.isEmpty()
        && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
      showPlus = s.charAt(0) == '+';
      i++;
    }
    int digits = 0;
    StringBuilder tail = new StringBuilder();
    for (; i < parts.size(); i++) {
      if (parts.get(i) instanceof Character ch && isPlaceholder(ch)) {
        digits++;
      } else {
        tail.append(parts.get(i));
      }
    }
    out.append(exponent < 0 ? "-" : showPlus ? "+" : "");
    out.append(String.format(Locale.ROOT, "%0" + Math.max(digits, 1) + "d", Math.abs(exponent)));
    return out.append(tail).toString();
  }

  private static String render(
      List<Object> parts,
      int end,
      BigDecimal value,
      int integerDigits,
      int decimalDigits,
      boolean grouping,
      boolean negative) {
    String plain = value.abs().toPlainString();
    int dot = plain.indexOf('.');
    String whole = dot < 0 ? plain : plain.substring(0, dot);
    String fraction = dot < 0 ? "" : plain.substring(dot + 1);
    if (whole.equals("0")) {
      whole = "";
    }
    // Fill integer placeholders from the right, with any surplus digits at the leftmost.
    StringBuilder out = new StringBuilder(negative ? "-" : "");
    int digitsLeft = whole.length();
    int placeholdersLeft = integerDigits;
    int surplus = Math.max(0, whole.length() - integerDigits);
    int used = 0;
    int fractionUsed = 0;
    boolean decimals = false;
    boolean firstPlaceholder = true;
    int remainingTotal = Math.max(whole.length(), integerDigits);
    for (int i = 0; i < end; i++) {
      Object part = parts.get(i);
      if (part instanceof String literal) {
        out.append(literal);
        continue;
      }
      char ch = (Character) part;
      if (ch == '%') {
        out.append('%');
      } else if (ch == '.') {
        decimals = true;
        if (decimalDigits > 0 || nextIsLiteralDot(parts, i)) {
          out.append('.');
        }
      } else if (isPlaceholder(ch)) {
        if (decimals) {
          if (fractionUsed < fraction.length()) {
            char digit = fraction.charAt(fractionUsed);
            boolean restZero = fraction.substring(fractionUsed).chars().allMatch(x -> x == '0');
            fractionUsed++;
            if (ch == '#' && restZero) {
              continue;
            }
            out.append(ch == '?' && restZero ? ' ' : digit);
          }
        } else {
          if (firstPlaceholder && surplus > 0) {
            for (int k = 0; k < surplus; k++) {
              out.append(whole.charAt(used++));
              digitsLeft--;
              remainingTotal--;
              appendGroup(out, grouping, remainingTotal);
            }
          }
          firstPlaceholder = false;
          if (digitsLeft >= placeholdersLeft && digitsLeft > 0) {
            out.append(whole.charAt(used++));
            digitsLeft--;
            remainingTotal--;
            appendGroup(out, grouping, remainingTotal);
          } else if (ch == '0') {
            out.append('0');
            remainingTotal--;
            appendGroup(out, grouping, remainingTotal);
          } else if (ch == '?') {
            out.append(' ');
            remainingTotal--;
          } else {
            remainingTotal--;
          }
          placeholdersLeft--;
        }
      }
    }
    return out.toString();
  }

  private static boolean nextIsLiteralDot(List<Object> parts, int index) {
    return index == parts.size() - 1;
  }

  private static void appendGroup(StringBuilder out, boolean grouping, int remaining) {
    if (grouping && remaining > 0 && remaining % 3 == 0) {
      out.append(',');
    }
  }
}
