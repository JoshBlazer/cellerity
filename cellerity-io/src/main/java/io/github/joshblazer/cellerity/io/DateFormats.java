package io.github.joshblazer.cellerity.io;

import java.util.Locale;

/** Decides whether a number format displays a date or time. */
final class DateFormats {
  private DateFormats() {}

  /** ECMA-376 built-in date ids, including the locale-specific ranges. */
  static boolean isBuiltInDate(int id) {
    return (id >= 14 && id <= 22)
        || (id >= 27 && id <= 36)
        || (id >= 45 && id <= 47)
        || (id >= 50 && id <= 58)
        || (id >= 71 && id <= 81);
  }

  /** Looks for date letters, ignoring quoted text, escapes, fill and padding, and bracket tags. */
  static boolean isDateCode(String code) {
    String lower = code.toLowerCase(Locale.ROOT);
    int length = lower.length();
    for (int i = 0; i < length; i++) {
      char c = lower.charAt(i);
      switch (c) {
        case '"' -> {
          int close = lower.indexOf('"', i + 1);
          if (close < 0) {
            return false;
          }
          i = close;
        }
        case '\\', '_', '*' -> i++;
        case '[' -> {
          int close = lower.indexOf(']', i + 1);
          if (close < 0) {
            return false;
          }
          if (isElapsedTime(lower.substring(i + 1, close))) {
            return true;
          }
          i = close;
        }
        case 'y', 'm', 'd', 'h', 's' -> {
          return true;
        }
        default -> {}
      }
    }
    return false;
  }

  /** True for the {@code [h]}, {@code [mm]}, and {@code [ss]} elapsed-time tags. */
  private static boolean isElapsedTime(String tag) {
    return !tag.isEmpty()
        && (tag.chars().allMatch(ch -> ch == 'h')
            || tag.chars().allMatch(ch -> ch == 'm')
            || tag.chars().allMatch(ch -> ch == 's'));
  }
}
