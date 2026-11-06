package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.n;
import static io.github.joshblazer.cellerity.core.functions.Args.num;

import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.Dates;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.Value;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;

final class DateFunctions {
  private static final double DAY_SECONDS = 86_400;

  private DateFunctions() {}

  static void register(Registry r) {
    r.scalar("DATE", 3, 3, (a, c) -> num(Dates.serial(n(a, 0), n(a, 1), n(a, 2), c.dates())));
    r.scalar("YEAR", 1, 1, (a, c) -> num(ymd(a, c).year()));
    r.scalar("MONTH", 1, 1, (a, c) -> num(ymd(a, c).month()));
    r.scalar("DAY", 1, 1, (a, c) -> num(ymd(a, c).day()));
    r.scalar("HOUR", 1, 1, (a, c) -> num(Math.floor(secondOfDay(n(a, 0)) / 3600)));
    r.scalar("MINUTE", 1, 1, (a, c) -> num(Math.floor(secondOfDay(n(a, 0)) / 60) % 60));
    r.scalar("SECOND", 1, 1, (a, c) -> num(secondOfDay(n(a, 0)) % 60));
    r.scalar(
        "TIME",
        3,
        3,
        (a, c) -> {
          double seconds =
              Math.floor(n(a, 0)) * 3600 + Math.floor(n(a, 1)) * 60 + Math.floor(n(a, 2));
          if (seconds < 0) {
            throw error(ExcelError.NUM);
          }
          return num((seconds % DAY_SECONDS) / DAY_SECONDS);
        });
    r.scalar(
        "WEEKDAY",
        1,
        2,
        (a, c) -> {
          double serial = n(a, 0);
          if (serial < 0) {
            throw error(ExcelError.NUM);
          }
          int sunday = Dates.dayOfWeekIndex(serial, c.dates());
          int type = (int) n(a, 1, 1);
          return num(
              switch (type) {
                case 1, 17 -> sunday + 1;
                case 2, 11 -> Math.floorMod(sunday - 1, 7) + 1;
                case 3 -> Math.floorMod(sunday - 1, 7);
                case 12, 13, 14, 15, 16 -> Math.floorMod(sunday - (type - 10), 7) + 1;
                default -> throw error(ExcelError.NUM);
              });
        });
    r.scalar("EDATE", 2, 2, (a, c) -> num(addMonths(n(a, 0), n(a, 1), c.dates(), false)));
    r.scalar("EOMONTH", 2, 2, (a, c) -> num(addMonths(n(a, 0), n(a, 1), c.dates(), true)));
    r.raw(
        "WORKDAY",
        2,
        3,
        (a, c) -> {
          double serial = Math.floor(single(a[0], c));
          double days = truncate(single(a[1], c));
          Set<Double> holidays = a.length > 2 ? holidays(a[2]) : Set.of();
          int step = days < 0 ? -1 : 1;
          double remaining = Math.abs(days);
          while (remaining > 0) {
            serial += step;
            if (serial < 0 || serial > Dates.maxSerial(c.dates())) {
              throw error(ExcelError.NUM);
            }
            if (isWorkday(serial, holidays, c.dates())) {
              remaining--;
            }
          }
          return num(serial);
        });
    r.raw(
        "NETWORKDAYS",
        2,
        3,
        (a, c) -> {
          double start = Math.floor(single(a[0], c));
          double end = Math.floor(single(a[1], c));
          Set<Double> holidays = a.length > 2 ? holidays(a[2]) : Set.of();
          double low = Math.min(start, end);
          double high = Math.max(start, end);
          int count = 0;
          for (double d = low; d <= high; d++) {
            if (isWorkday(d, holidays, c.dates())) {
              count++;
            }
          }
          return num(start <= end ? count : -count);
        });
    r.scalar("DAYS360", 2, 3, (a, c) -> num(days360(n(a, 0), n(a, 1), n(a, 2, 0) != 0, c.dates())));
    r.scalar("DAYS", 2, 2, (a, c) -> num(Math.floor(n(a, 0)) - Math.floor(n(a, 1))));
    r.scalar(
        "DATEVALUE",
        1,
        1,
        (a, c) -> {
          OptionalDouble parsed = Dates.parse(Args.s(a, 0), c.dates());
          if (parsed.isEmpty()) {
            throw error(ExcelError.VALUE);
          }
          return num(Math.floor(parsed.getAsDouble()));
        });
    r.scalar(
        "TIMEVALUE",
        1,
        1,
        (a, c) -> {
          OptionalDouble parsed = Dates.parse(Args.s(a, 0), c.dates());
          if (parsed.isEmpty()) {
            throw error(ExcelError.VALUE);
          }
          return num(parsed.getAsDouble() - Math.floor(parsed.getAsDouble()));
        });
    r.scalar(
        "WEEKNUM",
        1,
        2,
        (a, c) -> {
          double serial = n(a, 0);
          Dates.Ymd date = Dates.ymd(serial, c.dates());
          double first = Dates.serial(date.year(), 1, 1, c.dates());
          int firstSunday = Dates.dayOfWeekIndex(first, c.dates());
          int offset = n(a, 1, 1) == 2 ? Math.floorMod(firstSunday - 1, 7) : firstSunday;
          return num(Math.floor((Math.floor(serial) - first + offset) / 7) + 1);
        });
    r.scalar(
        "YEARFRAC", 2, 3, (a, c) -> num(yearFrac(n(a, 0), n(a, 1), (int) n(a, 2, 0), c.dates())));
    r.scalar("NOW", 0, 0, (a, c) -> num(c.now()));
    r.scalar("TODAY", 0, 0, (a, c) -> num(Math.floor(c.now())));
  }

  private static Dates.Ymd ymd(Value[] a, FunctionContext c) {
    return Dates.ymd(n(a, 0), c.dates());
  }

  private static double secondOfDay(double serial) {
    if (serial < 0) {
      throw error(ExcelError.NUM);
    }
    double fraction = serial - Math.floor(serial);
    return Math.floor(Math.round(fraction * DAY_SECONDS) % DAY_SECONDS);
  }

  private static double truncate(double value) {
    return value < 0 ? Math.ceil(value) : Math.floor(value);
  }

  private static double single(Value value, FunctionContext context) {
    return Coercion.toNumber(Coercion.intersect(value, context));
  }

  private static Set<Double> holidays(Value arg) {
    Set<Double> holidays = new HashSet<>();
    Args.each(
        arg,
        v -> {
          if (v instanceof Value.Err e) {
            throw error(e.error());
          }
          if (!(v instanceof Value.Blank)) {
            holidays.add(Math.floor(Coercion.toNumber(v)));
          }
        });
    return holidays;
  }

  private static boolean isWorkday(double serial, Set<Double> holidays, DateSystem system) {
    int sunday = Dates.dayOfWeekIndex(serial, system);
    return sunday != 0 && sunday != 6 && !holidays.contains(serial);
  }

  private static double addMonths(
      double serial, double months, DateSystem system, boolean endOfMonth) {
    Dates.Ymd date = Dates.ymd(serial, system);
    long total = date.year() * 12L + (date.month() - 1) + (long) truncate(months);
    long year = Math.floorDiv(total, 12);
    int month = Math.floorMod(total, 12) + 1;
    if (year < 1900 || year > 9999) {
      throw error(ExcelError.NUM);
    }
    int last = YearMonth.of((int) year, month).lengthOfMonth();
    int day = endOfMonth ? last : Math.min(date.day(), last);
    return Dates.serial((double) year, month, day, system);
  }

  private static double days360(double start, double end, boolean european, DateSystem system) {
    Dates.Ymd s = Dates.ymd(start, system);
    Dates.Ymd e = Dates.ymd(end, system);
    int startDay = s.day();
    int endDay = e.day();
    if (european) {
      startDay = Math.min(startDay, 30);
      endDay = Math.min(endDay, 30);
    } else {
      if (isLastOfFebruary(s)) {
        startDay = 30;
      }
      if (isLastOfFebruary(s) && isLastOfFebruary(e)) {
        endDay = 30;
      }
      if (endDay == 31 && startDay >= 30) {
        endDay = 30;
      }
      if (startDay == 31) {
        startDay = 30;
      }
    }
    return (e.year() - s.year()) * 360.0 + (e.month() - s.month()) * 30.0 + (endDay - startDay);
  }

  private static boolean isLastOfFebruary(Dates.Ymd date) {
    return date.month() == 2 && date.day() >= YearMonth.of(date.year(), 2).lengthOfMonth();
  }

  private static double yearFrac(double a, double b, int basis, DateSystem system) {
    double start = Math.floor(Math.min(a, b));
    double end = Math.floor(Math.max(a, b));
    return switch (basis) {
      case 0 -> days360(start, end, false, system) / 360;
      case 1 -> actualActual(start, end, system);
      case 2 -> (end - start) / 360;
      case 3 -> (end - start) / 365;
      case 4 -> days360(start, end, true, system) / 360;
      default -> throw error(ExcelError.NUM);
    };
  }

  private static double actualActual(double start, double end, DateSystem system) {
    Dates.Ymd s = Dates.ymd(start, system);
    Dates.Ymd e = Dates.ymd(end, system);
    double years;
    if (s.year() == e.year()) {
      years = YearMonth.of(s.year(), 1).lengthOfYear();
    } else if (e.year() - s.year() == 1
        && (e.month() < s.month() || (e.month() == s.month() && e.day() <= s.day()))) {
      boolean leapDay =
          (java.time.Year.isLeap(s.year()) && s.month() <= 2)
              || (java.time.Year.isLeap(e.year())
                  && (e.month() > 2 || (e.month() == 2 && e.day() == 29)));
      years = leapDay ? 366 : 365;
    } else {
      double total = 0;
      for (int y = s.year(); y <= e.year(); y++) {
        total += YearMonth.of(y, 1).lengthOfYear();
      }
      years = total / (e.year() - s.year() + 1);
    }
    return (end - start) / years;
  }
}
