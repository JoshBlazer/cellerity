package io.github.joshblazer.cellerity.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CellRefTest {
  @ParameterizedTest
  @CsvSource({"A1,1,1", "Z1,26,1", "AA1,27,1", "AZ9,52,9", "XFD1048576,16384,1048576"})
  void parsesAndPrints(String text, int column, int row) {
    CellRef ref = CellRef.parse(text).orElseThrow();
    assertEquals(new CellRef(column, row), ref);
    assertEquals(text, ref.toA1());
  }

  @Test
  void acceptsAbsoluteAndLowerCaseForms() {
    assertEquals(new CellRef(2, 3), CellRef.parse("$B$3").orElseThrow());
    assertEquals(new CellRef(2, 3), CellRef.parse("b$3").orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "A",
        "1",
        "A0",
        "A1048577",
        "XFE1",
        "A1:B2",
        "A-1",
        "1A",
        " A1",
        "A1 ",
        "$",
        "$A$"
      })
  void rejectsInvalidReferences(String text) {
    assertTrue(CellRef.parse(text).isEmpty(), text);
  }

  @Test
  void ordersByRowThenColumn() {
    assertTrue(new CellRef(5, 1).compareTo(new CellRef(1, 2)) < 0);
    assertTrue(new CellRef(1, 2).compareTo(new CellRef(2, 2)) < 0);
  }

  @Property
  void roundTripsEveryValidCell(
      @ForAll @IntRange(min = 1, max = CellRef.MAX_COLUMN) int column,
      @ForAll @IntRange(min = 1, max = CellRef.MAX_ROW) int row) {
    CellRef ref = new CellRef(column, row);
    assertEquals(ref, CellRef.parse(ref.toA1()).orElseThrow());
  }
}
