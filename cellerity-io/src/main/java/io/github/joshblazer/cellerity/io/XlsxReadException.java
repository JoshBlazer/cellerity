package io.github.joshblazer.cellerity.io;

/** A workbook could not be read. Expected for corrupt, hostile, or unsupported files. */
public final class XlsxReadException extends Exception {
  /** Why a read failed. */
  public enum Kind {
    /** Not an {@code .xlsx}: legacy {@code .xls}, encrypted, {@code .xlsb}, or not a workbook. */
    UNSUPPORTED_FORMAT,
    /** A zip package, but its content breaks the format. */
    MALFORMED,
    /** A {@link ReadLimits} limit was exceeded. */
    LIMIT_EXCEEDED,
    /** The file could not be read from disk. */
    IO
  }

  private final Kind kind;

  public XlsxReadException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public XlsxReadException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
