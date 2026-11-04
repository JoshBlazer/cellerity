package io.github.joshblazer.cellerity.io;

import java.nio.file.Path;

/** Reads {@code .xlsx} and {@code .xlsm} files. Thread-safe: holds no state between reads. */
public final class XlsxReader {
  private XlsxReader() {}

  /** Reads a workbook with {@link ReadLimits#DEFAULT}. */
  public static Workbook read(Path path) throws XlsxReadException {
    return read(path, ReadLimits.DEFAULT);
  }

  /** Reads a workbook. Every expected failure is an {@link XlsxReadException}. */
  public static Workbook read(Path path, ReadLimits limits) throws XlsxReadException {
    throw new UnsupportedOperationException("not implemented");
  }
}
