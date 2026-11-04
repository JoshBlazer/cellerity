package io.github.joshblazer.cellerity.io;

import io.github.joshblazer.cellerity.core.CellRef;
import java.util.Map;
import java.util.Optional;

/**
 * One sheet. {@code cells} holds only cells with a value or formula, in file order.
 *
 * @param cells unmodifiable; an absent cell is blank
 */
public record Sheet(String name, SheetKind kind, SheetState state, Map<CellRef, Cell> cells) {
  /** The cell at this position, or empty if the cell is blank. */
  public Optional<Cell> cell(CellRef ref) {
    return Optional.ofNullable(cells.get(ref));
  }
}
