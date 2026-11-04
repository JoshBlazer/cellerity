package io.github.joshblazer.cellerity.core;

import java.util.Arrays;
import java.util.Optional;

/**
 * Excel error values. The first seven are the classic errors. The rest appear as cached values in
 * files from newer Excel versions, so the reader must be able to carry them.
 */
public enum ExcelError {
  NULL("#NULL!"),
  DIV0("#DIV/0!"),
  VALUE("#VALUE!"),
  REF("#REF!"),
  NAME("#NAME?"),
  NUM("#NUM!"),
  NA("#N/A"),
  GETTING_DATA("#GETTING_DATA"),
  SPILL("#SPILL!"),
  CALC("#CALC!"),
  FIELD("#FIELD!"),
  BLOCKED("#BLOCKED!"),
  CONNECT("#CONNECT!"),
  BUSY("#BUSY!"),
  EXTERNAL("#EXTERNAL!"),
  UNKNOWN("#UNKNOWN!"),
  PYTHON("#PYTHON!");

  private final String code;

  ExcelError(String code) {
    this.code = code;
  }

  /** The text Excel displays and stores, such as {@code #DIV/0!}. */
  public String code() {
    return code;
  }

  /** Finds the error with this exact code. */
  public static Optional<ExcelError> fromCode(String code) {
    return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst();
  }
}
