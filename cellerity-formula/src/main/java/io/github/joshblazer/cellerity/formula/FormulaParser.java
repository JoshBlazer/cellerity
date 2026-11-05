package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.formula.Expr.BinaryOp;
import io.github.joshblazer.cellerity.formula.Expr.UnaryOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses formula text. Never throws on any input. See {@code docs/design/parser.md}. */
public final class FormulaParser {
  private static final int MAX_DEPTH = 512;
  private static final Set<Token.Kind> STARTS_REFERENCE =
      Set.of(
          Token.Kind.CELL,
          Token.Kind.AREA,
          Token.Kind.BROKEN_REF,
          Token.Kind.NAME,
          Token.Kind.FUNCTION,
          Token.Kind.STRUCTURED,
          Token.Kind.LPAREN);

  private final String text;
  private final List<Token> tokens;
  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private int index;
  private int depth;
  private boolean unionAllowed;
  private boolean aborted;

  private FormulaParser(String text) {
    this.text = text;
    this.tokens = Lexer.tokenize(text);
  }

  /** Parses a formula. Problems are returned as diagnostics, with recovery. */
  public static ParseResult parse(String text) {
    return new FormulaParser(text).parseFormula();
  }

  private ParseResult parseFormula() {
    if (isOperator("=")) {
      index++;
    }
    Expr expr = comparison();
    if (!aborted && peek().kind() != Token.Kind.EOF) {
      problem("Unexpected '" + peek().text() + "'", peek().span());
    }
    return new ParseResult(expr, diagnostics);
  }

  private Token peek() {
    return tokens.get(index);
  }

  private Token advance() {
    Token token = tokens.get(index);
    if (token.kind() != Token.Kind.EOF) {
      index++;
    }
    return token;
  }

  private boolean isOperator(String symbol) {
    Token token = peek();
    return token.kind() == Token.Kind.OPERATOR && token.text().equals(symbol);
  }

  private void problem(String message, Span span) {
    if (!aborted) {
      diagnostics.add(new Diagnostic(message, span));
    }
  }

  /** Skips to a place parsing can resume from. */
  private void synchronize() {
    while (true) {
      Token.Kind kind = peek().kind();
      if (kind == Token.Kind.COMMA || kind == Token.Kind.RPAREN || kind == Token.Kind.EOF) {
        return;
      }
      index++;
    }
  }

  private Expr comparison() {
    if (depth >= MAX_DEPTH) {
      problem("Formula is nested too deeply", peek().span());
      aborted = true;
      index = tokens.size() - 1;
      return invalid(peek().span());
    }
    depth++;
    Expr left = concat();
    while (peek().kind() == Token.Kind.OPERATOR) {
      BinaryOp op =
          switch (peek().text()) {
            case "=" -> BinaryOp.EQ;
            case "<>" -> BinaryOp.NE;
            case "<" -> BinaryOp.LT;
            case "<=" -> BinaryOp.LE;
            case ">" -> BinaryOp.GT;
            case ">=" -> BinaryOp.GE;
            default -> null;
          };
      if (op == null) {
        break;
      }
      advance();
      Expr right = concat();
      left = new Expr.Binary(op, left, right, left.span().to(right.span()));
    }
    depth--;
    return left;
  }

  private Expr concat() {
    Expr left = additive();
    while (isOperator("&")) {
      advance();
      Expr right = additive();
      left = new Expr.Binary(BinaryOp.CONCAT, left, right, left.span().to(right.span()));
    }
    return left;
  }

  private Expr additive() {
    Expr left = term();
    while (isOperator("+") || isOperator("-")) {
      BinaryOp op = advance().text().equals("+") ? BinaryOp.ADD : BinaryOp.SUBTRACT;
      Expr right = term();
      left = new Expr.Binary(op, left, right, left.span().to(right.span()));
    }
    return left;
  }

  private Expr term() {
    Expr left = power();
    while (isOperator("*") || isOperator("/")) {
      BinaryOp op = advance().text().equals("*") ? BinaryOp.MULTIPLY : BinaryOp.DIVIDE;
      Expr right = power();
      left = new Expr.Binary(op, left, right, left.span().to(right.span()));
    }
    return left;
  }

  private Expr power() {
    Expr left = postfix();
    while (isOperator("^")) {
      advance();
      Expr right = postfix();
      left = new Expr.Binary(BinaryOp.POWER, left, right, left.span().to(right.span()));
    }
    return left;
  }

  private Expr postfix() {
    Expr operand = unary();
    while (isOperator("%")) {
      Token percent = advance();
      operand = new Expr.Percent(operand, operand.span().to(percent.span()));
    }
    return operand;
  }

  private Expr unary() {
    if (isOperator("-") || isOperator("+")) {
      if (depth >= MAX_DEPTH) {
        return comparison();
      }
      depth++;
      Token op = advance();
      Expr operand = unary();
      depth--;
      return new Expr.Unary(
          op.text().equals("-") ? UnaryOp.NEGATE : UnaryOp.PLUS,
          operand,
          op.span().to(operand.span()));
    }
    return union();
  }

  private Expr union() {
    Expr left = intersect();
    while (unionAllowed && peek().kind() == Token.Kind.COMMA) {
      advance();
      Expr right = intersect();
      left = new Expr.Binary(BinaryOp.UNION, left, right, left.span().to(right.span()));
    }
    return left;
  }

  private Expr intersect() {
    Expr left = range();
    while (peek().spaceBefore()
        && STARTS_REFERENCE.contains(peek().kind())
        && referenceLike(left)) {
      Expr right = range();
      left = new Expr.Binary(BinaryOp.INTERSECT, left, right, left.span().to(right.span()));
    }
    return left;
  }

  private static boolean referenceLike(Expr expr) {
    return switch (expr) {
      case Expr.Cell c -> true;
      case Expr.Area a -> true;
      case Expr.Name n -> true;
      case Expr.Call c -> true;
      case Expr.Paren p -> true;
      case Expr.BrokenRef r -> true;
      case Expr.Structured s -> true;
      case Expr.Binary b ->
          b.op() == BinaryOp.RANGE || b.op() == BinaryOp.INTERSECT || b.op() == BinaryOp.UNION;
      default -> false;
    };
  }

  private Expr range() {
    Expr left = primary();
    while (peek().kind() == Token.Kind.COLON) {
      advance();
      Expr right = primary();
      if (left instanceof Expr.Cell from
          && right instanceof Expr.Cell to
          && (to.scope().equals(Expr.Scope.NONE) || to.scope().equals(from.scope()))) {
        left = new Expr.Area(from.scope(), from.at(), to.at(), from.span().to(to.span()));
      } else {
        left = new Expr.Binary(BinaryOp.RANGE, left, right, left.span().to(right.span()));
      }
    }
    return left;
  }

  private Expr primary() {
    Token token = peek();
    switch (token.kind()) {
      case NUMBER -> {
        advance();
        return new Expr.Num(token.number(), token.text(), token.span());
      }
      case STRING -> {
        advance();
        return new Expr.Str(token.text(), token.span());
      }
      case ERROR -> {
        advance();
        return new Expr.Err(token.error(), token.span());
      }
      case CELL -> {
        advance();
        return new Expr.Cell(token.scope(), token.from(), token.span());
      }
      case AREA -> {
        advance();
        return new Expr.Area(token.scope(), token.from(), token.to(), token.span());
      }
      case BROKEN_REF -> {
        advance();
        return new Expr.BrokenRef(token.scope(), token.span());
      }
      case STRUCTURED -> {
        advance();
        return new Expr.Structured(token.text(), token.span());
      }
      case NAME -> {
        advance();
        if (token.scope().equals(Expr.Scope.NONE)) {
          String upper = token.text().toUpperCase(Locale.ROOT);
          if (upper.equals("TRUE") || upper.equals("FALSE")) {
            return new Expr.Bool(upper.equals("TRUE"), token.span());
          }
        }
        return new Expr.Name(token.scope(), token.text(), token.span());
      }
      case FUNCTION -> {
        return call();
      }
      case LPAREN -> {
        return paren();
      }
      case LBRACE -> {
        return array();
      }
      case BAD -> {
        problem("Invalid text '" + token.text() + "'", token.span());
        advance();
        Span span = token.span();
        synchronize();
        return invalid(span);
      }
      default -> {
        problem(
            token.kind() == Token.Kind.EOF
                ? "Formula ended unexpectedly"
                : "Unexpected '" + token.text() + "'",
            token.span());
        Span span = token.span();
        synchronize();
        return invalid(span);
      }
    }
  }

  private Expr invalid(Span span) {
    return new Expr.Invalid(text.substring(span.start(), span.end()), span);
  }

  private Expr paren() {
    Token open = advance();
    boolean saved = unionAllowed;
    unionAllowed = true;
    Expr inner = comparison();
    unionAllowed = saved;
    return new Expr.Paren(inner, open.span().to(closeParen(inner.span())));
  }

  /** Consumes a closing parenthesis, reporting and recovering if it is missing. */
  private Span closeParen(Span fallback) {
    if (peek().kind() == Token.Kind.RPAREN) {
      return advance().span();
    }
    problem("Expected ')'", peek().span());
    synchronize();
    return peek().kind() == Token.Kind.RPAREN ? advance().span() : fallback;
  }

  private Expr call() {
    Token name = advance();
    advance(); // The lexer only emits FUNCTION directly before an opening parenthesis.
    boolean saved = unionAllowed;
    unionAllowed = false;
    List<Expr> args = new ArrayList<>();
    if (peek().kind() != Token.Kind.RPAREN) {
      while (true) {
        Token.Kind kind = peek().kind();
        if (kind == Token.Kind.COMMA || kind == Token.Kind.RPAREN) {
          args.add(new Expr.Missing(new Span(peek().span().start(), peek().span().start())));
        } else {
          args.add(comparison());
        }
        if (peek().kind() == Token.Kind.COMMA) {
          advance();
          continue;
        }
        break;
      }
    }
    unionAllowed = saved;
    Span last = args.isEmpty() ? name.span() : args.get(args.size() - 1).span();
    return new Expr.Call(name.text(), args, name.span().to(closeParen(last)));
  }

  private Expr array() {
    Token open = advance();
    List<List<Expr>> rows = new ArrayList<>();
    List<Expr> row = new ArrayList<>();
    while (true) {
      Expr element = arrayElement();
      if (element == null) {
        while (peek().kind() != Token.Kind.RBRACE && peek().kind() != Token.Kind.EOF) {
          index++;
        }
        break;
      }
      row.add(element);
      if (peek().kind() == Token.Kind.COMMA) {
        advance();
      } else if (peek().kind() == Token.Kind.SEMICOLON) {
        advance();
        rows.add(row);
        row = new ArrayList<>();
      } else {
        break;
      }
    }
    rows.add(row);
    Span end = peek().span();
    if (peek().kind() == Token.Kind.RBRACE) {
      advance();
    } else {
      problem("Expected '}'", peek().span());
    }
    return new Expr.ArrayLit(rows, open.span().to(end));
  }

  /** Reads one array constant element, or reports a problem and returns null. */
  private Expr arrayElement() {
    Token token = peek();
    if (isOperator("-") || isOperator("+")) {
      advance();
      Token number = peek();
      if (number.kind() != Token.Kind.NUMBER) {
        problem("Array constants may hold only constants", number.span());
        return null;
      }
      advance();
      return new Expr.Unary(
          token.text().equals("-") ? UnaryOp.NEGATE : UnaryOp.PLUS,
          new Expr.Num(number.number(), number.text(), number.span()),
          token.span().to(number.span()));
    }
    switch (token.kind()) {
      case NUMBER, STRING, ERROR -> {
        return primary();
      }
      case NAME -> {
        String upper = token.text().toUpperCase(Locale.ROOT);
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
          return primary();
        }
      }
      default -> {}
    }
    problem("Array constants may hold only constants", token.span());
    return null;
  }
}
