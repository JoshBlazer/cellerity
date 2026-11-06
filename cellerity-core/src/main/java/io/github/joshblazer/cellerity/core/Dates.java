package io.github.joshblazer.cellerity.core;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Serial arithmetic. See {@code docs/design/numerics.md}, section 2. */
public final class Dates {
  private static final long EPOCH_1900 = LocalDate.of(1899, 12, 30).toEpochDay();
  private static final long EPOCH_1904 = LocalDate.of(1904, 1, 1).toEpochDay();
  private static final int FICTITIOUS_LEAP_DAY = 60;
  private static final int MAX_SERIAL_1900 = 2_958_465;
  private static final int MAX_SERIAL_1904 = 2_957_003;
  private static final List<String> MONTHS =
      List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

  private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
  private static final Pattern SLASHED = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})");
  private static final Pattern DAY_MONTH_YEAR =
      Pattern.compile("(\\d{1,2})[- ]([A-Za-z]{3,9})[- ,]+(\\d{2,4})");
  private static final Pattern MONTH_DAY_YEAR =
      Pattern.compile("([A-Za-z]{3,9})\\.? (\\d{1,2}),? (\\d{2,4})");
  private static final Pattern TIME =
      Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}(?:\\.\\d+)?))?\\s*([AaPp])?[Mm]?");

  private Dates() {}

  /** A calendar date as Excel sees it, which can be the fictitious 1900-02-29 or 1900-01-00. */
  public record Ymd(int year, int month, int day) {}

  /** The date of a serial. Negative and too-large serials are {@code #NUM!}. */
  public static Ymd ymd(double serial, DateSystem system) {
    if (!(serial >= 0) || Math.floor(serial) > maxSerial(system)) {
      throw new FunctionError(ExcelError.NUM);
    }
    int whole = (int) Math.floor(serial);
    LocalDate date;
    if (system == DateSystem.SYSTEM_1904) {
      date = LocalDate.ofEpochDay(EPOCH_1904 + whole);
    } else if (whole == 0) {
      return new Ymd(1900, 1, 0);
    } else if (whole == FICTITIOUS_LEAP_DAY) {
      return new Ymd(1900, 2, 29);
    } else {
      date = LocalDate.ofEpochDay(EPOCH_1900 + whole + (whole < FICTITIOUS_LEAP_DAY ? 1 : 0));
    }
    return new Ymd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
  }

  public static int maxSerial(DateSystem system) {
    return system == DateSystem.SYSTEM_1904 ? MAX_SERIAL_1904 : MAX_SERIAL_1900;
  }

  /** {@code DATE}: months and days overflow, and years below 1900 are offsets from 1900. */
  public static double serial(double yearIn, double monthIn, double dayIn, DateSystem system) {
    long year = (long) Math.floor(yearIn);
    long month = (long) Math.floor(monthIn);
    long day = (long) Math.floor(dayIn);
    int floorYear = system == DateSystem.SYSTEM_1904 ? 1904 : 1900;
    if (year >= 0 && year < 1900) {
      year += 1900;
    }
    if (year < floorYear || year > 9999) {
      throw new FunctionError(ExcelError.NUM);
    }
    if (system == DateSystem.SYSTEM_1900 && year == 1900 && month == 2 && day == 29) {
      return FICTITIOUS_LEAP_DAY;
    }
    long months = year * 12 + (month - 1);
    long y = Math.floorDiv(months, 12);
    int m = Math.floorMod(months, 12) + 1;
    if (y < 1 || y > 9999) {
      throw new FunctionError(ExcelError.NUM);
    }
    LocalDate date;
    try {
      date = LocalDate.of((int) y, m, 1).plusDays(day - 1);
    } catch (DateTimeException e) {
      throw new FunctionError(ExcelError.NUM);
    }
    return serial(date, system);
  }

  /** The serial of a real calendar date. */
  public static double serial(LocalDate date, DateSystem system) {
    long serial;
    if (system == DateSystem.SYSTEM_1904) {
      serial = date.toEpochDay() - EPOCH_1904;
    } else {
      long difference = date.toEpochDay() - EPOCH_1900;
      serial = difference <= FICTITIOUS_LEAP_DAY ? difference - 1 : difference;
    }
    if (serial < 0 || serial > maxSerial(system)) {
      throw new FunctionError(ExcelError.NUM);
    }
    return serial;
  }

  /** Reads date or time text in common US forms. Other locales are not supported. */
  public static OptionalDouble parse(String text, DateSystem system) {
    String t = text.strip();
    double time = 0;
    Matcher timeMatch = TIME.matcher(t);
    boolean hasTime = false;
    int timeStart = t.length();
    while (timeMatch.find()) {
      if (timeMatch.end() == t.length()) {
        double hour = Double.parseDouble(timeMatch.group(1));
        String half = timeMatch.group(4);
        if (half != null) {
          if (hour < 1 || hour > 12) {
            return OptionalDouble.empty();
          }
          hour = hour % 12 + (half.equalsIgnoreCase("p") ? 12 : 0);
        }
        double minute = Double.parseDouble(timeMatch.group(2));
        double second = timeMatch.group(3) == null ? 0 : Double.parseDouble(timeMatch.group(3));
        if (minute >= 60 || second >= 60) {
          return OptionalDouble.empty();
        }
        time = (hour * 3600 + minute * 60 + second) / 86_400;
        hasTime = true;
        timeStart = timeMatch.start();
        break;
      }
    }
    String datePart = t.substring(0, timeStart).strip();
    if (datePart.isEmpty()) {
      return hasTime ? OptionalDouble.of(time) : OptionalDouble.empty();
    }
    OptionalDouble date = parseDate(datePart, system);
    return date.isPresent() ? OptionalDouble.of(date.getAsDouble() + time) : date;
  }

  private static OptionalDouble parseDate(String t, DateSystem system) {
    try {
      Matcher m = ISO.matcher(t);
      if (m.matches()) {
        return ofParts(num(m, 1), num(m, 2), num(m, 3), system);
      }
      m = SLASHED.matcher(t);
      if (m.matches()) {
        return ofParts(year(num(m, 3)), num(m, 1), num(m, 2), system);
      }
      m = DAY_MONTH_YEAR.matcher(t);
      if (m.matches()) {
        return ofParts(year(num(m, 3)), month(m.group(2)), num(m, 1), system);
      }
      m = MONTH_DAY_YEAR.matcher(t);
      if (m.matches()) {
        return ofParts(year(num(m, 3)), month(m.group(1)), num(m, 2), system);
      }
    } catch (DateTimeException | FunctionError e) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.empty();
  }

  private static OptionalDouble ofParts(int year, int month, int day, DateSystem system) {
    if (system == DateSystem.SYSTEM_1900 && year == 1900 && month == 2 && day == 29) {
      return OptionalDouble.of(FICTITIOUS_LEAP_DAY);
    }
    return OptionalDouble.of(serial(LocalDate.of(year, month, day), system));
  }

  private static int num(Matcher m, int group) {
    return Integer.parseInt(m.group(group));
  }

  private static int year(int year) {
    if (year < 30) {
      return 2000 + year;
    }
    return year < 100 ? 1900 + year : year;
  }

  private static int month(String name) {
    int index =
        name.length() < 3 ? -1 : MONTHS.indexOf(name.substring(0, 3).toLowerCase(Locale.ROOT));
    if (index < 0) {
      throw new DateTimeException(name);
    }
    return index + 1;
  }

  /** Days since a Sunday-based origin that matches Excel's weekday for this system. */
  public static int dayOfWeekIndex(double serial, DateSystem system) {
    long whole = (long) Math.floor(serial) + (system == DateSystem.SYSTEM_1904 ? 1462 : 0);
    return (int) Math.floorMod(whole - 1, 7L);
  }
}
