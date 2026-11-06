package io.github.joshblazer.cellerity.truffle;

import io.github.joshblazer.cellerity.formula.Expr;
import java.util.stream.Collectors;

/**
 * A formula as seen from its cell, in the manner of Excel's R1C1 notation. {@code =A1*2} in B1 and
 * {@code =A2*2} in B2 have one shape, so they can share nodes that read their position from the
 * frame. Without this, each cell of a model would have its own target, called once per
 * recalculation, and the JIT would rarely see one get hot.
 */
final class FormulaShape {
  private FormulaShape() {}

  static String of(Expr expr, int column, int row) {
    StringBuilder out = new StringBuilder();
    write(expr, column, row, out);
    return out.toString();
  }

  private static void write(Expr expr, int column, int row, StringBuilder out) {
    switch (expr) {
      case Expr.Num n -> out.append('n').append(Double.doubleToRawLongBits(n.value()));
      case Expr.Str s -> out.append('"').append(s.value().replace("\"", "\"\"")).append('"');
      case Expr.Bool b -> out.append(b.value());
      case Expr.Err e -> out.append(e.error().code());
      case Expr.Missing m -> out.append('_');
      case Expr.Cell c -> {
        scope(c.scope(), out);
        endpoint(c.at(), column, row, out);
      }
      case Expr.Area a -> {
        scope(a.scope(), out);
        endpoint(a.from(), column, row, out);
        out.append(':');
        endpoint(a.to(), column, row, out);
      }
      case Expr.BrokenRef r -> {
        scope(r.scope(), out);
        out.append("#REF!").append(r.tail());
      }
      case Expr.Name n -> {
        scope(n.scope(), out);
        out.append("name:").append(n.name().length()).append(':').append(n.name());
      }
      case Expr.Structured s -> out.append("table:").append(s.text().length()).append(s.text());
      case Expr.Invalid i -> out.append("invalid:").append(i.text().length()).append(i.text());
      case Expr.Paren p -> list("paren", java.util.List.of(p.inner()), column, row, out);
      case Expr.Percent p -> list("percent", java.util.List.of(p.operand()), column, row, out);
      case Expr.Unary u -> list(u.op().name(), java.util.List.of(u.operand()), column, row, out);
      case Expr.Binary b ->
          list(b.op().name(), java.util.List.of(b.left(), b.right()), column, row, out);
      case Expr.Call c -> list("call:" + c.name(), c.args(), column, row, out);
      case Expr.ArrayLit a -> {
        out.append("array[");
        for (var cells : a.rows()) {
          list("row", cells, column, row, out);
        }
        out.append(']');
      }
    }
  }

  private static void list(
      String head, java.util.List<Expr> children, int column, int row, StringBuilder out) {
    out.append(head).append('(');
    for (Expr child : children) {
      write(child, column, row, out);
      out.append(',');
    }
    out.append(')');
  }

  private static void scope(Expr.Scope scope, StringBuilder out) {
    out.append(
        java.util.stream.Stream.of(scope.workbook(), scope.sheet(), scope.lastSheet())
            .map(part -> part.map(text -> text.length() + ":" + text).orElse("-"))
            .collect(Collectors.joining("/", "<", ">")));
  }

  private static void endpoint(Expr.Endpoint at, int column, int row, StringBuilder out) {
    if (at.wholeColumn()) {
      out.append("R*");
    } else if (at.rowAbs()) {
      out.append('R').append(at.row());
    } else {
      out.append("R[").append(at.row() - row).append(']');
    }
    if (at.wholeRow()) {
      out.append("C*");
    } else if (at.colAbs()) {
      out.append('C').append(at.col());
    } else {
      out.append("C[").append(at.col() - column).append(']');
    }
  }
}
