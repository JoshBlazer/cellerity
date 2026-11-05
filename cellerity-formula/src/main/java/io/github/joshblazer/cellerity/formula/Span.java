package io.github.joshblazer.cellerity.formula;

/**
 * A half-open range of character positions in the formula text.
 *
 * @param start the first character
 * @param end one past the last character
 */
public record Span(int start, int end) {
  /** Used after {@link Exprs#withoutSpans}, and for nodes that have no source text. */
  public static final Span NONE = new Span(0, 0);

  /** The smallest span covering both. */
  public Span to(Span other) {
    return new Span(Math.min(start, other.start), Math.max(end, other.end));
  }
}
