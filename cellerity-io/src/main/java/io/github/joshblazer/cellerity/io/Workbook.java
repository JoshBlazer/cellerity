package io.github.joshblazer.cellerity.io;

import io.github.joshblazer.cellerity.core.DateSystem;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** An immutable loaded workbook. */
public record Workbook(
    List<Sheet> sheets, List<DefinedName> definedNames, DateSystem dateSystem, WorkbookInfo info) {
  public Workbook {
    sheets = List.copyOf(sheets);
    definedNames = List.copyOf(definedNames);
  }

  /** Finds a sheet by name, ignoring case as Excel does. */
  public Optional<Sheet> sheet(String name) {
    return sheets.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
  }

  /**
   * Finds a defined name, ignoring case. A name scoped to the given sheet wins over a global name
   * of the same spelling, as in Excel.
   *
   * @param localSheetIndex the sheet the lookup happens from, or empty for global names only
   */
  public Optional<DefinedName> definedName(String name, OptionalInt localSheetIndex) {
    Optional<DefinedName> local =
        localSheetIndex.isPresent()
            ? definedNames.stream()
                .filter(n -> n.name().equalsIgnoreCase(name))
                .filter(n -> n.localSheetIndex().equals(localSheetIndex))
                .findFirst()
            : Optional.empty();
    return local.isPresent()
        ? local
        : definedNames.stream()
            .filter(n -> n.name().equalsIgnoreCase(name))
            .filter(n -> n.localSheetIndex().isEmpty())
            .findFirst();
  }
}
