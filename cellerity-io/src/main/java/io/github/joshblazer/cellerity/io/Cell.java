package io.github.joshblazer.cellerity.io;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import java.util.Optional;

/**
 * A stored cell.
 *
 * @param value the stored value. For a formula cell this is the cached result, and it is empty if
 *     the file holds no cached result
 * @param formula the stored formula, if any
 * @param dateFormatted whether the cell's number format displays a date or time. The value stays a
 *     plain number
 */
public record Cell(
    CellRef ref, Optional<Value> value, Optional<Formula> formula, boolean dateFormatted) {}
