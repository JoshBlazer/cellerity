package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.core.ExcelError;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The formula AST. Nodes are immutable. Compare structure with {@link Exprs#withoutSpans}. */
public sealed interface Expr {
  /** Where this node came from in the text. */
  Span span();

  /** A number, keeping its source text. */
  record Num(double value, String text, Span span) implements Expr {}

  /** A string, with quotes removed and doubled quotes collapsed. */
  record Str(String value, Span span) implements Expr {}

  /** {@code TRUE} or {@code FALSE}. */
  record Bool(boolean value, Span span) implements Expr {}

  /** An error literal. */
  record Err(ExcelError error, Span span) implements Expr {}

  /** An empty argument, as in {@code IF(A1,,2)}. */
  record Missing(Span span) implements Expr {}

  /** A single cell. */
  record Cell(Scope scope, Endpoint at, Span span) implements Expr {}

  /** A rectangular area, including whole rows and columns. */
  record Area(Scope scope, Endpoint from, Endpoint to, Span span) implements Expr {}

  /**
   * A reference that is already broken, as in {@code Sheet1!#REF!}, or {@code #REF!B7} after a
   * sheet is deleted. It evaluates to {@code #REF!} either way.
   *
   * @param tail the address after {@code #REF!}, kept as written so it prints back, or empty. It is
   *     not shifted when a shared formula is expanded, since it refers to nothing.
   */
  record BrokenRef(Scope scope, String tail, Span span) implements Expr {}

  /** A defined name. */
  record Name(Scope scope, String name, Span span) implements Expr {}

  /** A structured table reference, kept as raw text. Not evaluated in v1. */
  record Structured(String text, Span span) implements Expr {}

  /** A function call. {@code name} is exactly as written. */
  record Call(String name, List<Expr> args, Span span) implements Expr {
    public Call {
      args = List.copyOf(args);
    }

    /** The upper-case name without storage prefixes such as {@code _xlfn.}. */
    public String canonicalName() {
      String upper = name.toUpperCase(Locale.ROOT);
      for (String prefix : List.of("_XLFN._XLWS.", "_XLFN.", "_XLWS.", "_XLL.")) {
        if (upper.startsWith(prefix)) {
          return upper.substring(prefix.length());
        }
      }
      return upper;
    }
  }

  /** A prefix operator. */
  record Unary(UnaryOp op, Expr operand, Span span) implements Expr {}

  /** The {@code %} operator. */
  record Percent(Expr operand, Span span) implements Expr {}

  /** A binary operator. */
  record Binary(BinaryOp op, Expr left, Expr right, Span span) implements Expr {}

  /** An array constant, by rows. */
  record ArrayLit(List<List<Expr>> rows, Span span) implements Expr {
    public ArrayLit {
      rows = rows.stream().map(List::copyOf).toList();
    }
  }

  /** Parentheses. */
  record Paren(Expr inner, Span span) implements Expr {}

  /** Where recovery happened. Evaluating a formula containing this is an unsupported feature. */
  record Invalid(String text, Span span) implements Expr {}

  /** Prefix operators. */
  enum UnaryOp {
    NEGATE("-"),
    PLUS("+");

    private final String symbol;

    UnaryOp(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }
  }

  /** Binary operators. */
  enum BinaryOp {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
    POWER("^"),
    CONCAT("&"),
    EQ("="),
    NE("<>"),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
    RANGE(":"),
    UNION(","),
    INTERSECT(" ");

    private final String symbol;

    BinaryOp(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }
  }

  /**
   * One corner of a reference, 1-based.
   *
   * @param col the column, or 0 in a whole-row reference
   * @param row the row, or 0 in a whole-column reference
   */
  record Endpoint(int col, int row, boolean colAbs, boolean rowAbs) {
    public boolean wholeColumn() {
      return row == 0;
    }

    public boolean wholeRow() {
      return col == 0;
    }
  }

  /**
   * What a reference is qualified by.
   *
   * @param workbook an external workbook, such as {@code 1} from {@code [1]Sheet!A1}
   * @param sheet the sheet, or the first sheet of a 3D reference
   * @param lastSheet the last sheet of a 3D reference
   */
  record Scope(Optional<String> workbook, Optional<String> sheet, Optional<String> lastSheet) {
    /** No qualifier: the sheet holding the formula. */
    public static final Scope NONE =
        new Scope(Optional.empty(), Optional.empty(), Optional.empty());

    public static Scope sheet(String name) {
      return new Scope(Optional.empty(), Optional.of(name), Optional.empty());
    }

    /** True for references that point outside this workbook. */
    public boolean external() {
      return workbook.isPresent();
    }

    /** True for references spanning several sheets. */
    public boolean threeD() {
      return lastSheet.isPresent();
    }
  }
}
