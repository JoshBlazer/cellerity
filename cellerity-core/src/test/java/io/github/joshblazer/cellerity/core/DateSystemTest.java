package io.github.joshblazer.cellerity.core;

import static io.github.joshblazer.cellerity.core.DateSystem.SYSTEM_1900;
import static io.github.joshblazer.cellerity.core.DateSystem.SYSTEM_1904;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DateSystemTest {
  @ParameterizedTest(name = "1900 system: serial {0} is {1}")
  @CsvSource({
    "0,1900-01-00",
    "1,1900-01-01",
    "31,1900-01-31",
    "59,1900-02-28",
    "60,1900-02-29", // Excel's deliberate leap-year bug
    "61,1900-03-01",
    "36526,2000-01-01",
    "2958465,9999-12-31",
  })
  void system1900(double serial, String expected) {
    assertEquals(expected, SYSTEM_1900.toIso(serial));
  }

  @ParameterizedTest(name = "1904 system: serial {0} is {1}")
  @CsvSource({
    "0,1904-01-01",
    "1,1904-01-02",
    "59,1904-02-29",
    "35064,2000-01-01",
    "2957003,9999-12-31"
  })
  void system1904(double serial, String expected) {
    assertEquals(expected, SYSTEM_1904.toIso(serial));
  }

  @ParameterizedTest
  @CsvSource({"-1", "2958466"})
  void outOfRangeSerialsHaveNoDate(double serial) {
    assertEquals("", SYSTEM_1900.toIso(serial));
  }

  @Test
  void rendersTimeOfDay() {
    assertEquals("2000-01-01T12:00:00", SYSTEM_1900.toIso(36526.5));
    assertEquals("2000-01-01T06:00:00", SYSTEM_1900.toIso(36526.25));
  }

  @Test
  void roundingCarriesIntoTheNextDay() {
    assertEquals("2000-01-02", SYSTEM_1900.toIso(36526.9999999999));
  }

  @Test
  void convertsDatesToSerials() {
    assertEquals(1.0, SYSTEM_1900.toSerial(LocalDate.of(1900, 1, 1).atStartOfDay()));
    assertEquals(59.0, SYSTEM_1900.toSerial(LocalDate.of(1900, 2, 28).atStartOfDay()));
    assertEquals(61.0, SYSTEM_1900.toSerial(LocalDate.of(1900, 3, 1).atStartOfDay()));
    assertEquals(36526.5, SYSTEM_1900.toSerial(LocalDateTime.of(2000, 1, 1, 12, 0)));
    assertEquals(0.0, SYSTEM_1904.toSerial(LocalDate.of(1904, 1, 1).atStartOfDay()));
  }

  @Test
  void rejectsDatesBeforeTheSystemStarts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SYSTEM_1904.toSerial(LocalDate.of(1903, 12, 31).atStartOfDay()));
    assertThrows(
        IllegalArgumentException.class,
        () -> SYSTEM_1900.toSerial(LocalDate.of(1899, 12, 31).atStartOfDay()));
  }

  @Test
  void systemsDifferBy1462DaysAfterTheBug() {
    LocalDateTime date = LocalDate.of(2024, 6, 30).atStartOfDay();
    assertEquals(1462.0, SYSTEM_1900.toSerial(date) - SYSTEM_1904.toSerial(date));
  }

  @Property
  void wholeSerialsRoundTrip1900(@ForAll @IntRange(min = 61, max = 2958465) int serial) {
    LocalDate date = LocalDate.parse(SYSTEM_1900.toIso(serial));
    assertEquals(serial, SYSTEM_1900.toSerial(date.atStartOfDay()));
  }

  @Property
  void wholeSerialsRoundTrip1904(@ForAll @IntRange(min = 0, max = 2957003) int serial) {
    LocalDate date = LocalDate.parse(SYSTEM_1904.toIso(serial));
    assertEquals(serial, SYSTEM_1904.toSerial(date.atStartOfDay()));
  }
}
