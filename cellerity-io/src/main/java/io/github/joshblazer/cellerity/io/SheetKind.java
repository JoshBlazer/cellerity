package io.github.joshblazer.cellerity.io;

/** What a sheet part contains. Only worksheets have cells to read. */
public enum SheetKind {
  WORKSHEET,
  CHARTSHEET,
  DIALOGSHEET,
  MACROSHEET,
  OTHER
}
