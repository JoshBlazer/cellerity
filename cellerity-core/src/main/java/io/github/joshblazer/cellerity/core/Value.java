package io.github.joshblazer.cellerity.core;

/** A cell value. Range and array values arrive with the evaluator in M2. */
public sealed interface Value {

  /** An IEEE 754 double. Dates are numbers; see {@code docs/design/numerics.md}. */
  record Num(double value) implements Value {}

  /** A string. */
  record Text(String value) implements Value {}

  /** {@code TRUE} or {@code FALSE}. */
  record Bool(boolean value) implements Value {}

  /** An Excel error as a first-class value. */
  record Err(ExcelError error) implements Value {}

  /** An empty cell. */
  record Blank() implements Value {}

  /** The shared blank value. */
  Value BLANK = new Blank();
}
