package io.github.joshblazer.cellerity.core.functions;

import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.Value;

/**
 * A library function.
 *
 * @param minArgs the fewest arguments allowed
 * @param maxArgs the most allowed
 */
public record FunctionDef(String name, int minArgs, int maxArgs, Mode mode, Body body) {
  /** How arguments reach the function. */
  public enum Mode {
    /** Arguments are single values. The function is lifted over arrays and ranges intersect. */
    SCALAR,
    /** Arguments arrive as they are, with references intact. */
    RAW,
    /** As {@link #RAW}, but arguments are also evaluated in array mode. */
    ARRAY
  }

  /** The implementation. */
  @FunctionalInterface
  public interface Body {
    Value apply(Value[] args, FunctionContext context);
  }
}
