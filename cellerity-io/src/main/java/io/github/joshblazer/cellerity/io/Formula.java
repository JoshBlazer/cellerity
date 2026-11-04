package io.github.joshblazer.cellerity.io;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * A formula exactly as stored, without the leading {@code =}.
 *
 * @param text the formula text; empty for shared-formula followers, whose text comes from the
 *     master in the same {@code sharedIndex} group (expansion is in M1)
 * @param sharedIndex the {@code si} group, for shared formulas
 * @param ref the range covered, for shared masters, array formulas, and data tables
 */
public record Formula(
    Kind kind, Optional<String> text, OptionalInt sharedIndex, Optional<String> ref) {
  /** The {@code t} attribute of {@code <f>}. */
  public enum Kind {
    NORMAL,
    SHARED,
    ARRAY,
    DATA_TABLE
  }
}
