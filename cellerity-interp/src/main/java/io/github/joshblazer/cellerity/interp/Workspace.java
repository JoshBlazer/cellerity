package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.CellSource;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.formula.Expr;
import java.util.Optional;
import java.util.OptionalInt;

/** What an evaluator needs from the workbook around a formula. */
public interface Workspace extends CellSource {
  DateSystem dates();

  /** The position of a sheet by name, ignoring case. */
  OptionalInt sheetIndex(String name);

  /** A defined name's formula, local scope first, written relative to {@code A1}. */
  Optional<Expr> name(String name, int fromSheet);

  double now();

  double random();
}
