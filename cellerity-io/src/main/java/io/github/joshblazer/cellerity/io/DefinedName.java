package io.github.joshblazer.cellerity.io;

import java.util.OptionalInt;

/**
 * A defined name.
 *
 * @param refersTo the stored formula text, such as {@code Sheet1!$A$1}
 * @param localSheetIndex the zero-based sheet this name is scoped to; empty for global names
 */
public record DefinedName(
    String name, String refersTo, OptionalInt localSheetIndex, boolean hidden) {}
