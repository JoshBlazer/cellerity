package io.github.joshblazer.cellerity.io;

import java.util.Optional;

/**
 * Facts that determine how far the cached values can be trusted. See {@code
 * docs/design/numerics.md}, section 5.
 *
 * @param application the writer named by {@code docProps/app.xml}. A label, not proof
 * @param calcMode {@code auto}, {@code autoNoTable}, or {@code manual}, if stated
 * @param fullCalcOnLoad whether the file asks for recalculation on open
 */
public record WorkbookInfo(
    Optional<String> application,
    Optional<String> appVersion,
    Optional<String> calcMode,
    boolean fullCalcOnLoad) {}
