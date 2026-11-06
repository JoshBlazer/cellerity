package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.Value;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** The comparison rules of {@code docs/design/numerics.md}, section 4. */
public final class Comparison {
  /** Provisional. Any change needs an ADR, and reports state the value in use. */
  public static final double TOLERANCE = 1e-12;

  private static final MathContext FIFTEEN = new MathContext(15, RoundingMode.HALF_EVEN);

  private Comparison() {}

  /** How an expected value compares with a calculated one, for cells that can be compared. */
  public static Category compare(Value expected, Value actual) {
    if (expected instanceof Value.Num a && actual instanceof Value.Num b) {
      return numbers(a.value(), b.value());
    }
    return expected.equals(actual) ? Category.EXACT : Category.REAL_MISMATCH;
  }

  private static Category numbers(double a, double b) {
    if (a == b) {
      return Category.EXACT;
    }
    if (Double.isNaN(a) || Double.isNaN(b) || Double.isInfinite(a) || Double.isInfinite(b)) {
      return Category.REAL_MISMATCH;
    }
    if (new BigDecimal(a).round(FIFTEEN).compareTo(new BigDecimal(b).round(FIFTEEN)) == 0) {
      return Category.DISPLAY_ONLY;
    }
    return Math.abs(a - b) <= TOLERANCE * Math.max(Math.abs(a), Math.abs(b))
        ? Category.WITHIN_TOLERANCE
        : Category.REAL_MISMATCH;
  }
}
