package io.github.joshblazer.cellerity.interp;

/** What became of one formula cell in a fidelity check. Only real mismatches count against. */
public enum Category {
  EXACT,
  DISPLAY_ONLY,
  WITHIN_TOLERANCE,
  UNSUPPORTED_FUNCTION,
  UNSUPPORTED_FEATURE,
  /** Volatile, or no cached value: nothing to compare with (ADR-0014). */
  NOT_COMPARABLE,
  REAL_MISMATCH
}
