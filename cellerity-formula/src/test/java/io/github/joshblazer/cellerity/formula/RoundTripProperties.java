package io.github.joshblazer.cellerity.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.ExcelError;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class RoundTripProperties {
  private static final Span S = Span.NONE;

  @Property(tries = 2000)
  void printThenParseGivesTheSameTree(@ForAll("expressions") Expr expr) {
    String text = FormulaPrinter.print(expr);
    ParseResult parsed = FormulaParser.parse(text);
    assertTrue(parsed.ok(), () -> text + " -> " + parsed.diagnostics());
    assertEquals(Exprs.withoutSpans(expr), Exprs.withoutSpans(parsed.expr()), text);
  }

  @Property(tries = 2000)
  void anyTextIsHandledWithSpansInBounds(@ForAll("formulaLikeText") String text) {
    ParseResult result = FormulaParser.parse(text);
    for (Diagnostic d : result.diagnostics()) {
      assertTrue(d.span().start() >= 0 && d.span().end() <= text.length(), () -> text + ": " + d);
      assertTrue(d.span().start() <= d.span().end(), () -> text + ": " + d);
    }
  }

  @Property(tries = 1000)
  void printingAParsedFormulaIsStable(@ForAll("formulaLikeText") String text) {
    ParseResult first = FormulaParser.parse(text);
    if (first.ok()) {
      String printed = FormulaPrinter.print(first.expr());
      ParseResult second = FormulaParser.parse(printed);
      assertTrue(second.ok(), printed);
      assertEquals(Exprs.withoutSpans(first.expr()), Exprs.withoutSpans(second.expr()), printed);
    }
  }

  @Provide
  Arbitrary<String> formulaLikeText() {
    Arbitrary<String> pieces =
        Arbitraries.of(
            "A1", "$B$2", "1", "2.5", "\"x\"", "SUM(", ")", "(", ",", "+", "-", "*", "/", "^", "%",
            "&", "=", "<", ">", "<>", ":", " ", "Sheet1!", "'a b'!", "Rate", "TRUE", "#N/A", "{",
            "}", ";", "A:A", "1:1", "[", "]", "#", "\"", "'", "!", "$", ".");
    Arbitrary<String> structured = pieces.list().ofMaxSize(12).map(list -> String.join("", list));
    return Arbitraries.oneOf(structured, Arbitraries.strings().ascii().ofMaxLength(24));
  }

  @Provide
  Arbitrary<Expr> expressions() {
    return Arbitraries.lazyOf(
        () -> leaf(),
        () -> leaf(),
        () -> leaf(),
        () -> binary(),
        () -> unary(),
        () -> percent(),
        () -> paren(),
        () -> call(),
        () -> union());
  }

  private Arbitrary<Expr> operand() {
    // Parenthesize compound operands, so the printed text has exactly the generated shape.
    return expressions()
        .map(e ->
            switch (e) {
              case Expr.Binary b -> new Expr.Paren(b, S);
              case Expr.Unary u -> new Expr.Paren(u, S);
              case Expr.Percent p -> new Expr.Paren(p, S);
              default -> e;
            });
  }

  private Arbitrary<Expr> binary() {
    List<Expr.BinaryOp> ops =
        Arrays.stream(Expr.BinaryOp.values())
            .filter(o ->
                o != Expr.BinaryOp.UNION
                    && o != Expr.BinaryOp.RANGE
                    && o != Expr.BinaryOp.INTERSECT)
            .toList();
    return Combinators.combine(Arbitraries.of(ops), operand(), operand())
        .as((op, l, r) -> new Expr.Binary(op, l, r, S));
  }

  private Arbitrary<Expr> union() {
    return Combinators.combine(cell(), area())
        .as((l, r) -> (Expr) new Expr.Paren(new Expr.Binary(Expr.BinaryOp.UNION, l, r, S), S));
  }

  private Arbitrary<Expr> unary() {
    return Combinators.combine(Arbitraries.of(Expr.UnaryOp.values()), operand())
        .as((op, e) -> new Expr.Unary(op, e, S));
  }

  private Arbitrary<Expr> percent() {
    return operand().map(e -> new Expr.Percent(e, S));
  }

  private Arbitrary<Expr> paren() {
    return expressions().map(e -> new Expr.Paren(e, S));
  }

  private Arbitrary<Expr> call() {
    Arbitrary<String> names = Arbitraries.of("SUM", "IF", "LOG10", "_xlfn.IFS", "my.fn", "Max");
    return Combinators.combine(names, operand().list().ofMaxSize(3))
        .as((n, args) -> new Expr.Call(n, args, S));
  }

  private Arbitrary<Expr> leaf() {
    return Arbitraries.oneOf(
        Arbitraries.of(
            new Expr.Num(1.5, "1.5", S), new Expr.Num(2, "2", S), new Expr.Num(1000, "1E3", S)),
        Arbitraries.of("", "a", "q\"uote", "it's", "a,b").map(s -> new Expr.Str(s, S)),
        Arbitraries.of(true, false).map(b -> new Expr.Bool(b, S)),
        Arbitraries.of(ExcelError.DIV0, ExcelError.NA, ExcelError.REF).map(e -> new Expr.Err(e, S)),
        cell(),
        area(),
        Arbitraries.of("Rate", "a.b", "_x", "XFE1").map(n -> new Expr.Name(Expr.Scope.NONE, n, S)),
        Arbitraries.of(new Expr.Structured("T[Col]", S)),
        array());
  }

  private Arbitrary<Expr.Scope> scope() {
    return Arbitraries.of(
        Expr.Scope.NONE,
        Expr.Scope.sheet("Sheet1"),
        Expr.Scope.sheet("My Sheet"),
        Expr.Scope.sheet("It's"),
        Expr.Scope.sheet("2008"),
        new Expr.Scope(Optional.empty(), Optional.of("Jan"), Optional.of("Dec")),
        new Expr.Scope(Optional.of("1"), Optional.of("Sheet1"), Optional.empty()));
  }

  private Arbitrary<Expr.Endpoint> endpoint() {
    return Combinators.combine(
            Arbitraries.integers().between(1, CellRef.MAX_COLUMN),
            Arbitraries.integers().between(1, CellRef.MAX_ROW),
            Arbitraries.of(true, false),
            Arbitraries.of(true, false))
        .as(Expr.Endpoint::new);
  }

  private Arbitrary<Expr> cell() {
    return Combinators.combine(scope(), endpoint()).as((s, e) -> new Expr.Cell(s, e, S));
  }

  private Arbitrary<Expr> area() {
    return Combinators.combine(scope(), endpoint(), endpoint())
        .as((s, a, b) -> new Expr.Area(s, a, b, S));
  }

  private Arbitrary<Expr> array() {
    Arbitrary<Expr> element =
        Arbitraries.of(
            new Expr.Num(1, "1", S),
            new Expr.Str("x", S),
            new Expr.Bool(true, S),
            new Expr.Err(ExcelError.NA, S),
            new Expr.Unary(Expr.UnaryOp.NEGATE, new Expr.Num(4, "4", S), S));
    return element
        .list()
        .ofMinSize(1)
        .ofMaxSize(3)
        .list()
        .ofMinSize(1)
        .ofMaxSize(3)
        .map(rows -> new Expr.ArrayLit(rows, S));
  }
}
