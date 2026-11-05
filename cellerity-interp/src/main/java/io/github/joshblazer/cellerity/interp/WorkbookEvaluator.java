package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;

/** Evaluates a whole workbook on demand. Signature first; implemented after its tests fail. */
public final class WorkbookEvaluator {
  public WorkbookEvaluator(ParsedWorkbook workbook) {
    if (workbook == null) {
      throw new IllegalArgumentException("workbook");
    }
  }

  /** The calculated value of a cell. */
  public Value value(int sheet, CellRef at) {
    throw new UnsupportedOperationException("not implemented: " + sheet + "!" + at);
  }
}
