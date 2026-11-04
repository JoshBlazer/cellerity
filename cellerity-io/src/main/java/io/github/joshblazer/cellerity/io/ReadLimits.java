package io.github.joshblazer.cellerity.io;

/**
 * Limits on what one read may consume. Exceeding one fails the read with {@link
 * XlsxReadException.Kind#LIMIT_EXCEEDED}.
 *
 * @param maxPartBytes the most decompressed bytes in one zip part
 * @param maxTotalBytes the most decompressed bytes across all parts
 * @param maxCells the most stored cells across all sheets
 */
public record ReadLimits(long maxPartBytes, long maxTotalBytes, long maxCells) {
  private static final long GIB = 1L << 30;

  /** Enough for very large real workbooks, while stopping zip bombs. */
  public static final ReadLimits DEFAULT = new ReadLimits(2 * GIB, 4 * GIB, 20_000_000);
}
