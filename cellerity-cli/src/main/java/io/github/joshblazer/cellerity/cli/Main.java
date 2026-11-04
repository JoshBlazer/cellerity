package io.github.joshblazer.cellerity.cli;

import java.io.PrintStream;

/** Entry point. Exit codes: 0 clean, 1 mismatches or unsupported features, 2 Cellerity error. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs a command and returns its exit code, without exiting the JVM. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    throw new UnsupportedOperationException("not implemented");
  }
}
