package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.io.Workbook;
import java.util.Map;
import java.util.Optional;

/** A workbook with every formula parsed, shared formulas expanded, and array members linked. */
public final class ParsedWorkbook {
  private ParsedWorkbook() {}

  /** Parses every formula in the workbook. */
  public static ParsedWorkbook of(Workbook workbook) {
    throw new UnsupportedOperationException("not implemented");
  }

  public Workbook workbook() {
    throw new UnsupportedOperationException("not implemented");
  }

  /** The formulas of a sheet by position. Array members are not included. */
  public Map<CellRef, ParsedFormula> formulas(int sheetIndex) {
    throw new UnsupportedOperationException("not implemented");
  }

  /** The master cell of the array range containing this cell, if it is a non-master member. */
  public Optional<CellRef> arrayMaster(int sheetIndex, CellRef cell) {
    throw new UnsupportedOperationException("not implemented");
  }
}
