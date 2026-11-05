package io.github.joshblazer.cellerity.formula;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Prints an AST as formula text that parses back to the same tree. */
public final class FormulaPrinter {
  private static final Pattern PLAIN_SHEET = Pattern.compile("[A-Za-z_\\\\][A-Za-z0-9_.]*");
  private static final Pattern LOOKS_LIKE_CELL = Pattern.compile("\\$?[A-Za-z]{1,3}\\$?\\d+");

  private FormulaPrinter() {}

  /** The formula text, without a leading equals sign. */
  public static String print(Expr expr) {
    return switch (expr) {
      case Expr.Num n -> n.text();
      case Expr.Str s -> '"' + s.value().replace("\"", "\"\"") + '"';
      case Expr.Bool b -> b.value() ? "TRUE" : "FALSE";
      case Expr.Err e -> e.error().code();
      case Expr.Missing m -> "";
      case Expr.Cell c -> scope(c.scope()) + endpoint(c.at());
      case Expr.Area a -> scope(a.scope()) + endpoint(a.from()) + ":" + endpoint(a.to());
      case Expr.BrokenRef r -> scope(r.scope()) + "#REF!" + r.tail();
      case Expr.Name n -> scope(n.scope()) + n.name();
      case Expr.Structured s -> s.text();
      case Expr.Call c ->
          c.name()
              + c.args().stream()
                  .map(FormulaPrinter::print)
                  .collect(Collectors.joining(",", "(", ")"));
      case Expr.Unary u -> u.op().symbol() + print(u.operand());
      case Expr.Percent p -> print(p.operand()) + "%";
      case Expr.Binary b -> print(b.left()) + b.op().symbol() + print(b.right());
      case Expr.Paren p -> "(" + print(p.inner()) + ")";
      case Expr.ArrayLit a ->
          a.rows().stream()
              .map(row -> row.stream().map(FormulaPrinter::print).collect(Collectors.joining(",")))
              .collect(Collectors.joining(";", "{", "}"));
      case Expr.Invalid i -> i.text();
    };
  }

  private static String endpoint(Expr.Endpoint e) {
    StringBuilder out = new StringBuilder();
    if (e.col() > 0) {
      out.append(e.colAbs() ? "$" : "").append(columnLetters(e.col()));
    }
    if (e.row() > 0) {
      out.append(e.rowAbs() ? "$" : "").append(e.row());
    }
    return out.toString();
  }

  /** The letters of a 1-based column. */
  public static String columnLetters(int column) {
    StringBuilder letters = new StringBuilder();
    for (int c = column; c > 0; c = (c - 1) / 26) {
      letters.insert(0, (char) ('A' + (c - 1) % 26));
    }
    return letters.toString();
  }

  private static String scope(Expr.Scope scope) {
    if (scope.equals(Expr.Scope.NONE)) {
      return "";
    }
    StringBuilder content = new StringBuilder();
    scope.workbook().ifPresent(w -> content.append('[').append(w).append(']'));
    scope.sheet().ifPresent(content::append);
    scope.lastSheet().ifPresent(s -> content.append(':').append(s));
    boolean plain =
        scope.sheet().map(FormulaPrinter::plainSheet).orElse(true)
            && scope.lastSheet().map(FormulaPrinter::plainSheet).orElse(true)
            && scope.workbook().map(w -> w.indexOf(' ') < 0 && w.indexOf('\'') < 0).orElse(true);
    return plain ? content + "!" : "'" + content.toString().replace("'", "''") + "'!";
  }

  private static boolean plainSheet(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    return PLAIN_SHEET.matcher(name).matches()
        && !LOOKS_LIKE_CELL.matcher(name).matches()
        && !upper.equals("TRUE")
        && !upper.equals("FALSE");
  }
}
