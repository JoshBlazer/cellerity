package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.ExcelError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits formula text into tokens. Reference forms are recognized here, not in the parser. */
final class Lexer {
  private static final Pattern WHOLE_COLUMNS =
      Pattern.compile("(\\$?)([A-Za-z]{1,3}):(\\$?)([A-Za-z]{1,3})");
  private static final Pattern WHOLE_ROWS = Pattern.compile("(\\$?)(\\d{1,7}):(\\$?)(\\d{1,7})");
  private static final Pattern CELL = Pattern.compile("(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7})");
  private static final List<ExcelError> ERRORS_LONGEST_FIRST =
      Arrays.stream(ExcelError.values())
          .sorted(Comparator.comparingInt((ExcelError e) -> -e.code().length()))
          .toList();

  private final String text;
  private final List<Token> tokens = new ArrayList<>();
  private int pos;
  private boolean space;

  private Lexer(String text) {
    this.text = text;
  }

  static List<Token> tokenize(String text) {
    Lexer lexer = new Lexer(text);
    lexer.run();
    return lexer.tokens;
  }

  private void run() {
    while (true) {
      space = skipWhitespace();
      if (pos >= text.length()) {
        add(Token.Kind.EOF, pos);
        return;
      }
      next();
    }
  }

  private boolean skipWhitespace() {
    int start = pos;
    while (pos < text.length() && isSpace(text.charAt(pos))) {
      pos++;
    }
    return pos > start;
  }

  private static boolean isSpace(char c) {
    return Character.isWhitespace(c) || c == ' ';
  }

  private void add(Token.Kind kind, int start) {
    tokens.add(Token.simple(kind, text.substring(start, pos), new Span(start, pos), space));
  }

  private void addScoped(
      Token.Kind kind, int start, Expr.Scope scope, Expr.Endpoint from, Expr.Endpoint to) {
    tokens.add(
        new Token(
            kind,
            text.substring(start, pos),
            new Span(start, pos),
            space,
            0,
            null,
            scope,
            from,
            to));
  }

  private void next() {
    int start = pos;
    char c = text.charAt(pos);
    switch (c) {
      case '(' -> single(Token.Kind.LPAREN);
      case ')' -> single(Token.Kind.RPAREN);
      case '{' -> single(Token.Kind.LBRACE);
      case '}' -> single(Token.Kind.RBRACE);
      case ',' -> single(Token.Kind.COMMA);
      case ';' -> single(Token.Kind.SEMICOLON);
      case ':' -> single(Token.Kind.COLON);
      case '+', '-', '*', '/', '^', '&', '=', '%' -> single(Token.Kind.OPERATOR);
      case '<' -> {
        pos++;
        if (pos < text.length() && (text.charAt(pos) == '=' || text.charAt(pos) == '>')) {
          pos++;
        }
        add(Token.Kind.OPERATOR, start);
      }
      case '>' -> {
        pos++;
        if (pos < text.length() && text.charAt(pos) == '=') {
          pos++;
        }
        add(Token.Kind.OPERATOR, start);
      }
      case '"' -> string();
      case '\'' -> quotedScope();
      case '[' -> bracket();
      case '#' -> errorLiteral();
      default -> {
        if (isDigit(c) || (c == '.' && pos + 1 < text.length() && isDigit(text.charAt(pos + 1)))) {
          numberOrRows();
        } else if (isNameChar(c)) {
          identifier();
        } else {
          pos++;
          add(Token.Kind.BAD, start);
        }
      }
    }
  }

  private void single(Token.Kind kind) {
    pos++;
    add(kind, pos - 1);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c)
        || c == '_'
        || c == '.'
        || c == '\\'
        || c == '?'
        || c == '$';
  }

  private void string() {
    int start = pos;
    StringBuilder value = new StringBuilder();
    pos++;
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c == '"') {
        if (pos + 1 < text.length() && text.charAt(pos + 1) == '"') {
          value.append('"');
          pos += 2;
          continue;
        }
        pos++;
        tokens.add(Token.simple(Token.Kind.STRING, value.toString(), new Span(start, pos), space));
        return;
      }
      value.append(c);
      pos++;
    }
    add(Token.Kind.BAD, start);
  }

  private void errorLiteral() {
    int start = pos;
    for (ExcelError error : ERRORS_LONGEST_FIRST) {
      if (text.startsWith(error.code(), pos)) {
        pos += error.code().length();
        tokens.add(
            new Token(
                Token.Kind.ERROR,
                error.code(),
                new Span(start, pos),
                space,
                0,
                error,
                Expr.Scope.NONE,
                null,
                null));
        return;
      }
    }
    pos++;
    while (pos < text.length()
        && (isNameChar(text.charAt(pos)) || "/!".indexOf(text.charAt(pos)) >= 0)) {
      pos++;
    }
    add(Token.Kind.BAD, start);
  }

  private void numberOrRows() {
    if (wholeRows(pos, Expr.Scope.NONE)) {
      return;
    }
    int start = pos;
    int end = pos;
    while (end < text.length() && isDigit(text.charAt(end))) {
      end++;
    }
    if (end < text.length() && text.charAt(end) == '!') {
      // An unquoted numeric sheet name. Excel quotes these, but other writers may not.
      Expr.Scope scope = Expr.Scope.sheet(text.substring(start, end));
      pos = end + 1;
      afterScope(start, scope);
      return;
    }
    if (end < text.length() && text.charAt(end) == '.') {
      end++;
      while (end < text.length() && isDigit(text.charAt(end))) {
        end++;
      }
    }
    if (end < text.length() && (text.charAt(end) == 'e' || text.charAt(end) == 'E')) {
      int exp = end + 1;
      if (exp < text.length() && (text.charAt(exp) == '+' || text.charAt(exp) == '-')) {
        exp++;
      }
      if (exp < text.length() && isDigit(text.charAt(exp))) {
        while (exp < text.length() && isDigit(text.charAt(exp))) {
          exp++;
        }
        end = exp;
      }
    }
    pos = end;
    String number = text.substring(start, end);
    tokens.add(
        new Token(
            Token.Kind.NUMBER,
            number,
            new Span(start, end),
            space,
            Double.parseDouble(number),
            null,
            Expr.Scope.NONE,
            null,
            null));
  }

  private boolean followedByNameOrScope(int end) {
    if (end >= text.length()) {
      return false;
    }
    char c = text.charAt(end);
    return isNameChar(c) || c == '!' || c == '(' || c == '[';
  }

  private boolean wholeRows(int at, Expr.Scope scope) {
    Matcher m = WHOLE_ROWS.matcher(text).region(at, text.length());
    if (!m.lookingAt() || followedByNameOrScope(m.end())) {
      return false;
    }
    int first = Integer.parseInt(m.group(2));
    int last = Integer.parseInt(m.group(4));
    if (first < 1 || last < 1 || first > CellRef.MAX_ROW || last > CellRef.MAX_ROW) {
      return false;
    }
    int start = tokenStart(at, scope);
    pos = m.end();
    addScoped(
        Token.Kind.AREA,
        start,
        scope,
        new Expr.Endpoint(0, first, false, !m.group(1).isEmpty()),
        new Expr.Endpoint(0, last, false, !m.group(3).isEmpty()));
    return true;
  }

  private boolean wholeColumns(int at, Expr.Scope scope) {
    Matcher m = WHOLE_COLUMNS.matcher(text).region(at, text.length());
    if (!m.lookingAt() || followedByNameOrScope(m.end())) {
      return false;
    }
    int first = column(m.group(2));
    int last = column(m.group(4));
    if (first > CellRef.MAX_COLUMN || last > CellRef.MAX_COLUMN) {
      return false;
    }
    int start = tokenStart(at, scope);
    pos = m.end();
    addScoped(
        Token.Kind.AREA,
        start,
        scope,
        new Expr.Endpoint(first, 0, !m.group(1).isEmpty(), false),
        new Expr.Endpoint(last, 0, !m.group(3).isEmpty(), false));
    return true;
  }

  private int scopeStart = -1;

  private int tokenStart(int at, Expr.Scope scope) {
    return scope.equals(Expr.Scope.NONE) || scopeStart < 0 ? at : scopeStart;
  }

  private static int column(String letters) {
    int column = 0;
    for (int i = 0; i < letters.length(); i++) {
      column = column * 26 + (Character.toUpperCase(letters.charAt(i)) - 'A' + 1);
    }
    return column;
  }

  private static Optional<Expr.Endpoint> cellEndpoint(String run) {
    Matcher m = CELL.matcher(run);
    if (!m.matches()) {
      return Optional.empty();
    }
    int column = column(m.group(2));
    int row = Integer.parseInt(m.group(4));
    if (column > CellRef.MAX_COLUMN || row < 1 || row > CellRef.MAX_ROW) {
      return Optional.empty();
    }
    return Optional.of(
        new Expr.Endpoint(column, row, !m.group(1).isEmpty(), !m.group(3).isEmpty()));
  }

  private int runEnd(int from) {
    int end = from;
    while (end < text.length() && isNameChar(text.charAt(end))) {
      end++;
    }
    return end;
  }

  private void identifier() {
    int start = pos;
    if (wholeColumns(pos, Expr.Scope.NONE) || wholeRows(pos, Expr.Scope.NONE)) {
      return;
    }
    int end = runEnd(pos);
    String run = text.substring(start, end);
    if (end < text.length() && text.charAt(end) == '!') {
      pos = end + 1;
      scopeStart = start;
      afterScope(start, Expr.Scope.sheet(run));
      return;
    }
    if (end < text.length() && text.charAt(end) == ':') {
      int lastEnd = runEnd(end + 1);
      if (lastEnd > end + 1 && lastEnd < text.length() && text.charAt(lastEnd) == '!') {
        String last = text.substring(end + 1, lastEnd);
        pos = lastEnd + 1;
        scopeStart = start;
        afterScope(start, new Expr.Scope(Optional.empty(), Optional.of(run), Optional.of(last)));
        return;
      }
    }
    pos = end;
    if (pos < text.length() && text.charAt(pos) == '(') {
      add(Token.Kind.FUNCTION, start);
      return;
    }
    if (pos < text.length() && text.charAt(pos) == '[') {
      structured(start);
      return;
    }
    plainReference(start, run, Expr.Scope.NONE);
  }

  /** Lexes the run as a cell or name. */
  private void plainReference(int start, String run, Expr.Scope scope) {
    Optional<Expr.Endpoint> cell = cellEndpoint(run);
    if (cell.isPresent()) {
      addScoped(Token.Kind.CELL, start, scope, cell.get(), null);
    } else if (run.indexOf('$') >= 0 || run.isEmpty()) {
      add(Token.Kind.BAD, start);
    } else {
      tokens.add(
          new Token(Token.Kind.NAME, run, new Span(start, pos), space, 0, null, scope, null, null));
    }
  }

  /** Lexes what follows a {@code scope!} prefix. {@code pos} is just past the bang. */
  private void afterScope(int start, Expr.Scope scope) {
    scopeStart = start;
    if (text.startsWith("#REF!", pos)) {
      pos += "#REF!".length();
      addScoped(Token.Kind.BROKEN_REF, start, scope, null, null);
    } else if (!wholeColumns(pos, scope) && !wholeRows(pos, scope)) {
      int end = runEnd(pos);
      String run = text.substring(pos, end);
      pos = end;
      if (pos < text.length() && text.charAt(pos) == '(') {
        add(Token.Kind.FUNCTION, start);
      } else {
        plainReference(start, run, scope);
      }
    }
    scopeStart = -1;
  }

  private void quotedScope() {
    int start = pos;
    StringBuilder name = new StringBuilder();
    int i = pos + 1;
    boolean closed = false;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\'') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
          name.append('\'');
          i += 2;
          continue;
        }
        closed = true;
        i++;
        break;
      }
      name.append(c);
      i++;
    }
    if (!closed || i >= text.length() || text.charAt(i) != '!') {
      pos = closed ? i : text.length();
      add(Token.Kind.BAD, start);
      return;
    }
    pos = i + 1;
    afterScope(start, parseQuotedScope(name.toString()));
  }

  private static Expr.Scope parseQuotedScope(String content) {
    String rest = content;
    Optional<String> workbook = Optional.empty();
    if (rest.startsWith("[")) {
      int close = rest.indexOf(']');
      if (close > 0) {
        workbook = Optional.of(rest.substring(1, close));
        rest = rest.substring(close + 1);
      }
    }
    int colon = rest.indexOf(':');
    if (colon > 0) {
      return new Expr.Scope(
          workbook, Optional.of(rest.substring(0, colon)), Optional.of(rest.substring(colon + 1)));
    }
    return new Expr.Scope(
        workbook, rest.isEmpty() ? Optional.empty() : Optional.of(rest), Optional.empty());
  }

  private void bracket() {
    int start = pos;
    int close = text.indexOf(']', pos);
    if (close > 0) {
      int end = runEnd(close + 1);
      if (end < text.length() && text.charAt(end) == '!') {
        String sheet = text.substring(close + 1, end);
        Expr.Scope scope =
            new Expr.Scope(
                Optional.of(text.substring(start + 1, close)),
                sheet.isEmpty() ? Optional.empty() : Optional.of(sheet),
                Optional.empty());
        pos = end + 1;
        afterScope(start, scope);
        return;
      }
    }
    structured(start);
  }

  /** Reads balanced brackets from {@code pos}. {@code '} escapes the next character inside them. */
  private void structured(int start) {
    int depth = 0;
    int i = pos;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\'') {
        i++;
      } else if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
        if (depth == 0) {
          pos = i + 1;
          add(Token.Kind.STRUCTURED, start);
          return;
        }
      }
      i++;
    }
    pos = text.length();
    add(Token.Kind.BAD, start);
  }
}
