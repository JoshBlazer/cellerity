package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.core.ExcelError;

/** One lexical token. Only the fields its kind needs are set. */
record Token(
    Kind kind,
    String text,
    Span span,
    boolean spaceBefore,
    double number,
    ExcelError error,
    Expr.Scope scope,
    Expr.Endpoint from,
    Expr.Endpoint to) {

  enum Kind {
    NUMBER,
    STRING,
    ERROR,
    CELL,
    AREA,
    BROKEN_REF,
    NAME,
    FUNCTION,
    STRUCTURED,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    COMMA,
    SEMICOLON,
    COLON,
    OPERATOR,
    BAD,
    EOF
  }

  static Token simple(Kind kind, String text, Span span, boolean space) {
    return new Token(kind, text, span, space, 0, null, Expr.Scope.NONE, null, null);
  }
}
