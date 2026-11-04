package io.github.joshblazer.cellerity.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Loads every workbook under the given directories and reports what happened. A typed rejection is
 * an acceptable outcome for a bad file. A crash, meaning any other throwable or a timeout, is a
 * bug.
 *
 * <p>Usage: {@code CorpusLoad --corpus DIR [--corpus DIR ...] --out FILE}. Exit 0 if nothing
 * crashed, 1 if something did, and 2 on bad usage.
 */
public final class CorpusLoad {
  private CorpusLoad() {}

  public static void main(String[] args) throws IOException {
    throw new UnsupportedOperationException("not implemented");
  }

  /** Loads all {@code .xlsx} and {@code .xlsm} files under the roots. */
  static CorpusResult run(List<Path> roots, Duration perFileTimeout) throws IOException {
    throw new UnsupportedOperationException("not implemented");
  }
}
