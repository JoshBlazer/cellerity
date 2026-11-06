package io.github.joshblazer.cellerity.core;

/** What a function may know about where it is called from. */
public interface FunctionContext {
  DateSystem dates();

  int sheet();

  /** The 1-based column of the formula's cell. */
  int column();

  /** The 1-based row of the formula's cell. */
  int row();

  CellSource source();

  /** Whether ranges stay arrays here, rather than intersecting with the formula's cell. */
  boolean arrayMode();

  /** Resolves {@code INDIRECT} text to a reference, or returns {@code #REF!}. */
  Value indirect(String text);

  /** The current time as a serial, for {@code NOW}. */
  double now();

  /** A number in [0, 1), for {@code RAND}. */
  double random();
}
