package io.github.joshblazer.cellerity.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.formula.Expr.BinaryOp;
import io.github.joshblazer.cellerity.formula.Expr.UnaryOp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FormulaParserTest {
  private static Expr parse(String text) {
    ParseResult result = FormulaParser.parse(text);
    assertTrue(result.ok(), () -> text + ": " + result.diagnostics());
    return result.expr();
  }

  /** The fully parenthesized shape of a formula, which makes precedence easy to assert. */
  private static String shape(Expr e) {
    return switch (e) {
      case Expr.Binary b ->
          "(" + shape(b.left()) + " " + b.op().symbol().strip() + " " + shape(b.right()) + ")";
      case Expr.Unary u -> "(" + u.op().symbol() + shape(u.operand()) + ")";
      case Expr.Percent p -> "(" + shape(p.operand()) + "%)";
      case Expr.Paren p -> "[" + shape(p.inner()) + "]";
      default -> FormulaPrinter.print(e);
    };
  }

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "-2^2|((-2) ^ 2)", // the documented quirk: this evaluates to 4
        "2^-3|(2 ^ (-3))",
        "2^3^2|((2 ^ 3) ^ 2)", // left associative
        "1+2*3|(1 + (2 * 3))",
        "1*2+3|((1 * 2) + 3)",
        "1-2-3|((1 - 2) - 3)",
        "8/4/2|((8 / 4) / 2)",
        "2*3^2|(2 * (3 ^ 2))",
        "50%|(50%)",
        "50%%|((50%)%)",
        "-50%|((-50)%)",
        "2^50%|(2 ^ (50%))",
        "1&2+3|(1 & (2 + 3))",
        "1+2&3|((1 + 2) & 3)",
        "1=2&3|(1 = (2 & 3))",
        "1<2=3|((1 < 2) = 3)",
        "1<=2|(1 <= 2)",
        "1>=2|(1 >= 2)",
        "1<>2|(1 <> 2)",
        "--A1|(-(-A1))",
        "+-1|(+(-1))",
        "(1+2)*3|([(1 + 2)] * 3)",
        "A1:B2 B1:C3|(A1:B2  B1:C3)",
        "A1 + B1|(A1 + B1)",
        "-A1:B2|(-A1:B2)",
        "A1:INDEX(B1:B3,2)|(A1 : INDEX(B1:B3,2))",
      })
  void precedence(String formula, String expected) {
    assertEquals(expected, shape(parse(formula)));
  }

  @Test
  void numbers() {
    assertEquals(1.5, ((Expr.Num) parse("1.5")).value());
    assertEquals(0.5, ((Expr.Num) parse(".5")).value());
    assertEquals(1000.0, ((Expr.Num) parse("1E3")).value());
    assertEquals(0.01, ((Expr.Num) parse("1e-2")).value());
    assertEquals(1.0, ((Expr.Num) parse("1.")).value());
    assertEquals("1.50", ((Expr.Num) parse("1.50")).text());
  }

  @Test
  void strings() {
    assertEquals("a\"b", ((Expr.Str) parse("\"a\"\"b\"")).value());
    assertEquals("", ((Expr.Str) parse("\"\"")).value());
    assertEquals("it's, (ok)", ((Expr.Str) parse("\"it's, (ok)\"")).value());
  }

  @Test
  void booleans() {
    assertEquals(true, ((Expr.Bool) parse("TRUE")).value());
    assertEquals(false, ((Expr.Bool) parse("false")).value());
    assertInstanceOf(Expr.Call.class, parse("TRUE()"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A"})
  void errors(String code) {
    assertEquals(ExcelError.fromCode(code).orElseThrow(), ((Expr.Err) parse(code)).error());
  }

  @Test
  void cellReferences() {
    Expr.Cell plain = (Expr.Cell) parse("B3");
    assertEquals(new Expr.Endpoint(2, 3, false, false), plain.at());
    assertEquals(Expr.Scope.NONE, plain.scope());
    assertEquals(new Expr.Endpoint(2, 3, true, true), ((Expr.Cell) parse("$B$3")).at());
    assertEquals(new Expr.Endpoint(2, 3, true, false), ((Expr.Cell) parse("$b3")).at());
    assertEquals(
        new Expr.Endpoint(16384, 1048576, false, false), ((Expr.Cell) parse("XFD1048576")).at());
  }

  @Test
  void areas() {
    Expr.Area area = (Expr.Area) parse("A1:$C$3");
    assertEquals(new Expr.Endpoint(1, 1, false, false), area.from());
    assertEquals(new Expr.Endpoint(3, 3, true, true), area.to());
  }

  @Test
  void wholeColumnsAndRows() {
    Expr.Area columns = (Expr.Area) parse("A:$C");
    assertTrue(columns.from().wholeColumn());
    assertEquals(1, columns.from().col());
    assertEquals(3, columns.to().col());
    assertTrue(columns.to().colAbs());
    Expr.Area rows = (Expr.Area) parse("$2:5");
    assertTrue(rows.from().wholeRow());
    assertEquals(2, rows.from().row());
    assertEquals(5, rows.to().row());
    assertInstanceOf(Expr.Call.class, ((Expr.Call) parse("SUM(D:D)")));
  }

  @ParameterizedTest(name = "{0} scoped to sheet {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "Sheet1!A1|Sheet1",
        "sheet_1.x!A1|sheet_1.x",
        "'My Sheet'!A1|My Sheet",
        "'It''s'!A1|It's",
        "'2008'!$A$1|2008",
        "'a!b'!A1|a!b",
      })
  void sheetQualifiedCells(String formula, String sheet) {
    Expr.Cell cell = (Expr.Cell) parse(formula);
    assertEquals(sheet, cell.scope().sheet().orElseThrow());
    assertFalse(cell.scope().external());
  }

  @Test
  void sheetQualifiedAreaBothForms() {
    Expr.Area one = (Expr.Area) parse("Sheet1!A1:B2");
    assertEquals("Sheet1", one.scope().sheet().orElseThrow());
    Expr.Area two = (Expr.Area) parse("Sheet1!A1:Sheet1!B2");
    assertEquals("Sheet1", two.scope().sheet().orElseThrow());
    assertEquals(2, two.to().col());
    Expr.Area columns = (Expr.Area) parse("'S 1'!A:B");
    assertEquals("S 1", columns.scope().sheet().orElseThrow());
  }

  @Test
  void differentSheetsOnARangeAreARangeOperator() {
    assertInstanceOf(Expr.Binary.class, parse("Sheet1!A1:Sheet2!B2"));
  }

  @Test
  void threeDReferences() {
    Expr.Cell cell = (Expr.Cell) parse("Jan:Dec!B2");
    assertEquals("Jan", cell.scope().sheet().orElseThrow());
    assertEquals("Dec", cell.scope().lastSheet().orElseThrow());
    assertTrue(cell.scope().threeD());
    assertTrue(((Expr.Area) parse("'Q 1:Q 4'!A1:B2")).scope().threeD());
  }

  @Test
  void externalReferences() {
    Expr.Cell indexed = (Expr.Cell) parse("[1]Sheet1!A1");
    assertEquals("1", indexed.scope().workbook().orElseThrow());
    assertEquals("Sheet1", indexed.scope().sheet().orElseThrow());
    assertTrue(indexed.scope().external());
    Expr.Cell quoted = (Expr.Cell) parse("'[Book 1.xlsx]My Sheet'!A1");
    assertEquals("Book 1.xlsx", quoted.scope().workbook().orElseThrow());
    assertEquals("My Sheet", quoted.scope().sheet().orElseThrow());
    assertTrue(((Expr.Name) parse("[1]!Rate")).scope().external());
  }

  @Test
  void brokenReferences() {
    assertEquals("Sheet1", ((Expr.BrokenRef) parse("Sheet1!#REF!")).scope().sheet().orElseThrow());
    assertInstanceOf(Expr.Err.class, parse("#REF!"));
  }

  /**
   * Excel writes this after the sheet a reference pointed at is deleted. Found in the Enron set.
   */
  @ParameterizedTest
  @ValueSource(strings = {"#REF!B167", "#REF!$B$7", "#REF!A1:B2", "#REF!B167/0.85*0.152"})
  void deletedSheetReferencesRoundTrip(String text) {
    ParseResult result = FormulaParser.parse(text);
    assertTrue(result.ok(), () -> text + " " + result.diagnostics());
    assertEquals(text, FormulaPrinter.print(result.expr()));
    assertTrue(Exprs.any(result.expr(), Expr.BrokenRef.class::isInstance));
  }

  @Test
  void deletedSheetReferenceNeedsAWholeCellAddress() {
    assertFalse(FormulaParser.parse("#REF!B").ok());
    assertFalse(FormulaParser.parse("#REF!B7x").ok());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Rate",
        "rate_2",
        "_private",
        "a.b",
        "XFE1",
        "A1B",
        "ABCD1",
        "A0",
        "\\back",
        "R1C1x"
      })
  void definedNames(String name) {
    assertEquals(name, ((Expr.Name) parse(name)).name());
  }

  @Test
  void sheetScopedName() {
    Expr.Name name = (Expr.Name) parse("Sheet1!Rate");
    assertEquals("Rate", name.name());
    assertEquals("Sheet1", name.scope().sheet().orElseThrow());
  }

  @Test
  void functionCalls() {
    Expr.Call call = (Expr.Call) parse("SUM(A1,B1:B2,3)");
    assertEquals("SUM", call.canonicalName());
    assertEquals(3, call.args().size());
    assertEquals(0, ((Expr.Call) parse("NOW()")).args().size());
    assertEquals("LOG10", ((Expr.Call) parse("LOG10(1)")).canonicalName());
    assertEquals("sum", ((Expr.Call) parse("sum(1)")).name());
    assertEquals("SUM", ((Expr.Call) parse("sum(1)")).canonicalName());
    assertEquals("IFS", ((Expr.Call) parse("_xlfn.IFS(TRUE,1)")).canonicalName());
    assertEquals("FILTER", ((Expr.Call) parse("_xlfn._xlws.FILTER(A1:A2,B1:B2)")).canonicalName());
  }

  @Test
  void emptyArguments() {
    Expr.Call call = (Expr.Call) parse("IF(A1,,2)");
    assertEquals(3, call.args().size());
    assertInstanceOf(Expr.Missing.class, call.args().get(1));
    assertInstanceOf(Expr.Missing.class, ((Expr.Call) parse("F(,)")).args().get(0));
    assertEquals(2, ((Expr.Call) parse("F(,)")).args().size());
    assertEquals(2, ((Expr.Call) parse("F(1,)")).args().size());
  }

  @Test
  void commaIsUnionOnlyInsideParentheses() {
    assertEquals(2, ((Expr.Call) parse("SUM(A1,B1)")).args().size());
    Expr.Call union = (Expr.Call) parse("SUM((A1,B1))");
    assertEquals(1, union.args().size());
    Expr.Paren paren = (Expr.Paren) union.args().get(0);
    assertEquals(BinaryOp.UNION, ((Expr.Binary) paren.inner()).op());
    Expr.Call nested = (Expr.Call) parse("SUM((A1,B1),C1)");
    assertEquals(2, nested.args().size());
    assertEquals(BinaryOp.UNION, ((Expr.Binary) ((Expr.Paren) parse("(A1,B1,C1)")).inner()).op());
  }

  @Test
  void intersectionNeedsReferences() {
    assertEquals(BinaryOp.INTERSECT, ((Expr.Binary) parse("A:A 1:1")).op());
    assertEquals(BinaryOp.INTERSECT, ((Expr.Binary) parse("Rate Term")).op());
    assertFalse(FormulaParser.parse("1 2").ok());
  }

  @Test
  void arrayConstants() {
    Expr.ArrayLit array = (Expr.ArrayLit) parse("{1,2;3,-4}");
    assertEquals(2, array.rows().size());
    assertEquals(2, array.rows().get(1).size());
    Expr.Unary negative = (Expr.Unary) array.rows().get(1).get(1);
    assertEquals(UnaryOp.NEGATE, negative.op());
    Expr.ArrayLit mixed = (Expr.ArrayLit) parse("{\"a\",TRUE,#N/A}");
    assertInstanceOf(Expr.Str.class, mixed.rows().get(0).get(0));
    assertInstanceOf(Expr.Bool.class, mixed.rows().get(0).get(1));
    assertInstanceOf(Expr.Err.class, mixed.rows().get(0).get(2));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Table1[Col]", "Table1[[#All],[Col A]]", "[@Price]", "T[[#This Row],[a]:[b]]"})
  void structuredReferencesKeepTheirText(String text) {
    assertEquals(text, ((Expr.Structured) parse(text)).text());
  }

  @Test
  void toleratesLeadingEqualsAndWhitespace() {
    assertEquals(1.0, ((Expr.Num) parse("=1")).value());
    assertEquals(1.0, ((Expr.Num) parse(" \r\n 1 \t")).value());
    assertEquals(2, ((Expr.Call) parse("SUM( 1 ,\n 2 )")).args().size());
  }

  // Spans and recovery

  @Test
  void spansAreExact() {
    Expr.Binary sum = (Expr.Binary) parse("A1 + SUM(B2:C3)");
    assertEquals(new Span(0, 2), sum.left().span());
    Expr.Call call = (Expr.Call) sum.right();
    assertEquals(new Span(5, 15), call.span());
    assertEquals(new Span(9, 14), call.args().get(0).span());
    assertEquals(new Span(0, 15), sum.span());
  }

  private static void assertProblemAt(String text, int position) {
    ParseResult result = FormulaParser.parse(text);
    assertFalse(result.ok(), text);
    assertEquals(
        position,
        result.diagnostics().get(0).span().start(),
        () -> result.diagnostics().toString());
  }

  @Test
  void diagnosticsPointAtTheProblem() {
    assertProblemAt("1+", 2);
    assertProblemAt("SUM(1,2", 7);
    assertProblemAt(")", 0);
    assertProblemAt("1+*2", 2);
    assertProblemAt("(1", 2);
    assertProblemAt("\"open", 0);
    assertProblemAt("'open", 0);
    assertProblemAt("{1,A1}", 3);
    assertProblemAt("1 2", 2);
    assertProblemAt("#BAD!", 0);
    assertProblemAt("", 0);
  }

  @Test
  void recoveryReportsSeveralProblemsAndKeepsTheRest() {
    ParseResult result = FormulaParser.parse("SUM(1+,2,3*)+A1");
    assertEquals(2, result.diagnostics().size(), () -> result.diagnostics().toString());
    Expr.Binary top = assertInstanceOf(Expr.Binary.class, result.expr());
    assertEquals(3, ((Expr.Call) top.left()).args().size());
    assertInstanceOf(Expr.Cell.class, top.right());
    assertTrue(Exprs.any(result.expr(), e -> e instanceof Expr.Invalid));
  }

  @Test
  void excessiveNestingIsADiagnosticNotACrash() {
    String deep = "(".repeat(100_000) + "1" + ")".repeat(100_000);
    assertFalse(FormulaParser.parse(deep).ok());
  }

  @Test
  void nestingBeyondExcelsOwnLimitStillParses() {
    String deep = "(".repeat(100) + "1" + ")".repeat(100);
    assertTrue(FormulaParser.parse(deep).ok());
  }

  @Test
  void aSmallStackGivesADiagnosticOrAResultButNeverAnError() throws InterruptedException {
    String deep = "(".repeat(120) + "1" + ")".repeat(120);
    Throwable[] thrown = new Throwable[1];
    Thread thread = new Thread(null, () -> FormulaParser.parse(deep), "small-stack", 32 * 1024);
    thread.setUncaughtExceptionHandler((t, e) -> thrown[0] = e);
    thread.start();
    thread.join();
    assertEquals(null, thrown[0]);
  }

  // Generic helpers

  @Test
  void functionNamesIncludeNestedCalls() {
    assertEquals(
        List.of("IF", "SUM", "MAX"), Exprs.functionNames(parse("if(sum(1),_xlfn.MAX(2),3)")));
  }

  // Shifting

  @ParameterizedTest(name = "{0} moved {1} columns, {2} rows is {3}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "A1|1|1|B2",
        "$A1|1|1|$A2",
        "A$1|1|1|B$1",
        "$A$1|1|1|$A$1",
        "A1:B2+C3|1|0|B1:C2+D3",
        "Sheet1!A1|0|2|Sheet1!A3",
        "SUM(A:B)|2|5|SUM(C:D)",
        "SUM($A:B)|2|5|SUM($A:D)",
        "SUM(1:2)|2|5|SUM(6:7)",
        "B2|-1|-1|A1",
        "A1|-1|-1|XFD1048576", // wraps at the sheet limits
        "Rate+1|3|3|Rate+1",
      })
  void shifting(String formula, int columns, int rows, String expected) {
    assertEquals(
        expected, FormulaPrinter.print(ReferenceShifter.shift(parse(formula), columns, rows)));
  }
}
