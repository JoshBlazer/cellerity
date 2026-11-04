package io.github.joshblazer.cellerity.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Excel's date systems. See {@code docs/design/numerics.md}, section 2. */
public enum DateSystem {
  SYSTEM_1900(2_958_465),
  SYSTEM_1904(2_957_003);

  private static final double MILLIS_PER_DAY = 86_400_000.0;
  private static final double NANOS_PER_DAY = 86_400_000_000_000.0;
  private static final int FICTITIOUS_LEAP_DAY = 60;
  private static final LocalDate EPOCH_1900_BEFORE_BUG = LocalDate.of(1899, 12, 31);
  private static final LocalDate EPOCH_1900_AFTER_BUG = LocalDate.of(1899, 12, 30);
  private static final LocalDate FIRST_DAY_1900 = LocalDate.of(1900, 1, 1);
  private static final LocalDate FIRST_DAY_AFTER_BUG = LocalDate.of(1900, 3, 1);
  private static final LocalDate EPOCH_1904 = LocalDate.of(1904, 1, 1);

  private final int maxSerial;

  DateSystem(int maxSerial) {
    this.maxSerial = maxSerial;
  }

  /**
   * Renders a serial as ISO text. Text is required because serial 60 in the 1900 system is the
   * fictitious {@code 1900-02-29}, which {@code java.time} cannot represent.
   *
   * @return {@code yyyy-MM-dd}, with {@code THH:mm:ss} or {@code THH:mm:ss.SSS} when there is a
   *     time part; empty text if the serial is outside this system's valid range
   */
  public String toIso(double serial) {
    if (!(serial >= 0) || serial >= maxSerial + 1) {
      return "";
    }
    long totalMillis = Math.round(serial * MILLIS_PER_DAY);
    long day = totalMillis / (long) MILLIS_PER_DAY;
    long millisOfDay = totalMillis % (long) MILLIS_PER_DAY;
    if (day > maxSerial) {
      return "";
    }
    return dateText((int) day) + timeText(millisOfDay);
  }

  private String dateText(int day) {
    if (this == SYSTEM_1904) {
      return EPOCH_1904.plusDays(day).toString();
    }
    if (day == 0) {
      return "1900-01-00";
    }
    if (day == FICTITIOUS_LEAP_DAY) {
      return "1900-02-29";
    }
    return (day < FICTITIOUS_LEAP_DAY ? EPOCH_1900_BEFORE_BUG : EPOCH_1900_AFTER_BUG)
        .plusDays(day)
        .toString();
  }

  private static String timeText(long millisOfDay) {
    if (millisOfDay == 0) {
      return "";
    }
    long seconds = millisOfDay / 1000;
    long millis = millisOfDay % 1000;
    String time =
        String.format(
            java.util.Locale.ROOT,
            "T%02d:%02d:%02d",
            seconds / 3600,
            seconds / 60 % 60,
            seconds % 60);
    return millis == 0 ? time : time + String.format(java.util.Locale.ROOT, ".%03d", millis);
  }

  /** The serial for a date and time. Dates before the system's first day are rejected. */
  public double toSerial(LocalDateTime dateTime) {
    LocalDate date = dateTime.toLocalDate();
    long day =
        switch (this) {
          case SYSTEM_1904 -> ChronoUnit.DAYS.between(EPOCH_1904, date);
          case SYSTEM_1900 -> {
            if (date.isBefore(FIRST_DAY_1900)) {
              yield -1;
            }
            yield ChronoUnit.DAYS.between(
                date.isBefore(FIRST_DAY_AFTER_BUG) ? EPOCH_1900_BEFORE_BUG : EPOCH_1900_AFTER_BUG,
                date);
          }
        };
    if (day < 0 || day > maxSerial) {
      throw new IllegalArgumentException(date + " is outside the " + this + " range");
    }
    return day + dateTime.toLocalTime().toNanoOfDay() / NANOS_PER_DAY;
  }
}
