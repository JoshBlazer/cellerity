package io.github.joshblazer.cellerity.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cases in {@code function-cases.tsv}, one per line: formula, expected, optional delta. Values come
 * from Microsoft's function documentation unless a comment says otherwise.
 */
class FunctionCasesTest {
  static Stream<Arguments> cases() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    try (InputStream in = FunctionCasesTest.class.getResourceAsStream("function-cases.tsv");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#!")) {
          continue;
        }
        String[] parts = line.split("	", -1);
        cases.add(
            Arguments.of(
                parts[0], parts[1], parts.length > 2 ? Double.parseDouble(parts[2]) : 0.0));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("cases")
  void matchesDocumentedValue(String formula, String expected, double delta) {
    Map<String, Object> cells = TestBooks.fixture();
    cells.put("Z100", "=" + formula);
    WorkbookEvaluator evaluator =
        new WorkbookEvaluator(ParsedWorkbook.of(TestBooks.workbook(cells)));
    Value actual = evaluator.value(0, CellRef.parse("Z100").orElseThrow());
    check(expected, delta, actual);
  }

  private static void check(String expected, double delta, Value actual) {
    if (expected.startsWith("\"")) {
      assertEquals(new Value.Text(expected.substring(1, expected.length() - 1)), actual);
    } else if (expected.equals("TRUE") || expected.equals("FALSE")) {
      assertEquals(new Value.Bool(expected.equals("TRUE")), actual);
    } else if (expected.startsWith("#")) {
      assertEquals(new Value.Err(ExcelError.fromCode(expected).orElseThrow()), actual);
    } else {
      Value.Num number = assertInstanceOf(Value.Num.class, actual);
      assertEquals(Double.parseDouble(expected), number.value(), delta == 0 ? 1e-12 : delta);
    }
  }
}
