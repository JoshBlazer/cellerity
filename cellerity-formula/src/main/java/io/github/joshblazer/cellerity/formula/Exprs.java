package io.github.joshblazer.cellerity.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Generic operations on the AST. */
public final class Exprs {
  private Exprs() {}

  /** The direct children of a node. */
  public static List<Expr> children(Expr expr) {
    return switch (expr) {
      case Expr.Call call -> call.args();
      case Expr.Unary unary -> List.of(unary.operand());
      case Expr.Percent percent -> List.of(percent.operand());
      case Expr.Binary binary -> List.of(binary.left(), binary.right());
      case Expr.Paren paren -> List.of(paren.inner());
      case Expr.ArrayLit array -> array.rows().stream().flatMap(List::stream).toList();
      case Expr.Num n -> List.of();
      case Expr.Str s -> List.of();
      case Expr.Bool b -> List.of();
      case Expr.Err e -> List.of();
      case Expr.Missing m -> List.of();
      case Expr.Cell c -> List.of();
      case Expr.Area a -> List.of();
      case Expr.BrokenRef r -> List.of();
      case Expr.Name n -> List.of();
      case Expr.Structured s -> List.of();
      case Expr.Invalid i -> List.of();
    };
  }

  /** Visits a node and all its descendants, parents first. */
  public static void walk(Expr expr, Consumer<Expr> visitor) {
    visitor.accept(expr);
    for (Expr child : children(expr)) {
      walk(child, visitor);
    }
  }

  /** The canonical names of all functions called, including nested calls. */
  public static List<String> functionNames(Expr expr) {
    List<String> names = new ArrayList<>();
    walk(
        expr,
        node -> {
          if (node instanceof Expr.Call call) {
            names.add(call.canonicalName());
          }
        });
    return names;
  }

  /** True if any node satisfies the test. */
  public static boolean any(Expr expr, java.util.function.Predicate<Expr> test) {
    if (test.test(expr)) {
      return true;
    }
    for (Expr child : children(expr)) {
      if (any(child, test)) {
        return true;
      }
    }
    return false;
  }

  /** True if the formula calls any of the named functions. */
  public static boolean callsAny(Expr expr, Set<String> canonicalNames) {
    return any(expr, n -> n instanceof Expr.Call c && canonicalNames.contains(c.canonicalName()));
  }

  /** Rebuilds the tree with {@code leaf} applied to reference leaves. */
  public static Expr mapLeaves(Expr expr, UnaryOperator<Expr> leaf) {
    return switch (expr) {
      case Expr.Call c ->
          new Expr.Call(
              c.name(), c.args().stream().map(a -> mapLeaves(a, leaf)).toList(), c.span());
      case Expr.Unary u -> new Expr.Unary(u.op(), mapLeaves(u.operand(), leaf), u.span());
      case Expr.Percent p -> new Expr.Percent(mapLeaves(p.operand(), leaf), p.span());
      case Expr.Binary b ->
          new Expr.Binary(b.op(), mapLeaves(b.left(), leaf), mapLeaves(b.right(), leaf), b.span());
      case Expr.Paren p -> new Expr.Paren(mapLeaves(p.inner(), leaf), p.span());
      case Expr.ArrayLit a ->
          new Expr.ArrayLit(
              a.rows().stream()
                  .map(row -> row.stream().map(e -> mapLeaves(e, leaf)).toList())
                  .toList(),
              a.span());
      default -> leaf.apply(expr);
    };
  }

  /** The same tree with every span replaced by {@link Span#NONE}, for structural comparison. */
  public static Expr withoutSpans(Expr expr) {
    Span none = Span.NONE;
    return switch (expr) {
      case Expr.Num n -> new Expr.Num(n.value(), n.text(), none);
      case Expr.Str s -> new Expr.Str(s.value(), none);
      case Expr.Bool b -> new Expr.Bool(b.value(), none);
      case Expr.Err e -> new Expr.Err(e.error(), none);
      case Expr.Missing m -> new Expr.Missing(none);
      case Expr.Cell c -> new Expr.Cell(c.scope(), c.at(), none);
      case Expr.Area a -> new Expr.Area(a.scope(), a.from(), a.to(), none);
      case Expr.BrokenRef r -> new Expr.BrokenRef(r.scope(), r.tail(), none);
      case Expr.Name n -> new Expr.Name(n.scope(), n.name(), none);
      case Expr.Structured s -> new Expr.Structured(s.text(), none);
      case Expr.Invalid i -> new Expr.Invalid(i.text(), none);
      case Expr.Call c ->
          new Expr.Call(c.name(), c.args().stream().map(Exprs::withoutSpans).toList(), none);
      case Expr.Unary u -> new Expr.Unary(u.op(), withoutSpans(u.operand()), none);
      case Expr.Percent p -> new Expr.Percent(withoutSpans(p.operand()), none);
      case Expr.Binary b ->
          new Expr.Binary(b.op(), withoutSpans(b.left()), withoutSpans(b.right()), none);
      case Expr.Paren p -> new Expr.Paren(withoutSpans(p.inner()), none);
      case Expr.ArrayLit a ->
          new Expr.ArrayLit(
              a.rows().stream().map(row -> row.stream().map(Exprs::withoutSpans).toList()).toList(),
              none);
    };
  }
}
