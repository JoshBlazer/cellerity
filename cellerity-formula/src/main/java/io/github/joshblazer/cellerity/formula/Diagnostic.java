package io.github.joshblazer.cellerity.formula;

/** A problem found while parsing, at a precise position. */
public record Diagnostic(String message, Span span) {}
