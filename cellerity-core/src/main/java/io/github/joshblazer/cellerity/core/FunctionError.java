package io.github.joshblazer.cellerity.core;

/**
 * A function's failure as an Excel error. Cheap enough to throw per cell, since it has no stack.
 */
public final class FunctionError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final ExcelError error;

  public FunctionError(ExcelError error) {
    super(error.code(), null, false, false);
    this.error = error;
  }

  public ExcelError error() {
    return error;
  }
}
