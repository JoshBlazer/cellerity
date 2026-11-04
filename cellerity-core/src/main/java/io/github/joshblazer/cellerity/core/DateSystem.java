package io.github.joshblazer.cellerity.core;

import java.time.LocalDateTime;

/** Excel's date systems. See {@code docs/design/numerics.md}, section 2. */
public enum DateSystem {
  SYSTEM_1900,
  SYSTEM_1904;

  /**
   * Renders a serial as ISO text. Text is required because serial 60 in the 1900 system is the
   * fictitious {@code 1900-02-29}, which {@code java.time} cannot represent.
   *
   * @return {@code yyyy-MM-dd}, with {@code THH:mm:ss} or {@code THH:mm:ss.SSS} when there is a
   *     time part; empty text if the serial is outside this system's valid range
   */
  public String toIso(double serial) {
    throw new UnsupportedOperationException("not implemented");
  }

  /** The serial for a date and time. Dates before the system's first day are rejected. */
  public double toSerial(LocalDateTime dateTime) {
    throw new UnsupportedOperationException("not implemented");
  }
}
