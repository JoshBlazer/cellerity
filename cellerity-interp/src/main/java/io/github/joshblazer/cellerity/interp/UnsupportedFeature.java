package io.github.joshblazer.cellerity.interp;

/** Evaluation met something out of scope. Unwinds to the formula's cell. */
public final class UnsupportedFeature extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public UnsupportedFeature(String feature) {
    super(feature, null, false, false);
  }
}
