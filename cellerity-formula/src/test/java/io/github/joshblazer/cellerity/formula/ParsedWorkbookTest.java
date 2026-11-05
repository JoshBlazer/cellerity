package io.github.joshblazer.cellerity.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.XlsxReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ParsedWorkbookTest {
  private static ParsedWorkbook sample(String path) throws Exception {
    Workbook workbook = XlsxReader.read(Path.of("../corpus/samples", path));
    return ParsedWorkbook.of(workbook);
  }

  private static CellRef at(String a1) {
    return CellRef.parse(a1).orElseThrow();
  }

  private static String text(ParsedWorkbook parsed, int sheet, String a1) {
    return FormulaPrinter.print(parsed.formulas(sheet).get(at(a1)).expr());
  }

  @Test
  void parsesOrdinaryFormulas() throws Exception {
    ParsedWorkbook parsed = sample("synthetic/sample.xlsx");
    assertEquals("B1*'Rates & Terms'!B1", text(parsed, 0, "B2"));
    assertEquals(2, parsed.formulas(0).size());
    assertTrue(parsed.formulas(1).isEmpty());
  }

  @Test
  void expandsSharedFormulaFollowers() throws Exception {
    ParsedWorkbook parsed = sample("poi/shared_formulas.xlsx");
    assertEquals("B2", text(parsed, 0, "A2"));
    assertEquals("B3", text(parsed, 0, "A3"));
    assertEquals("B5", text(parsed, 0, "A5"));
    assertEquals(ParsedFormula.Kind.SHARED, parsed.formulas(0).get(at("A3")).kind());
    assertTrue(parsed.formulas(0).get(at("A3")).ok());
  }

  @Test
  void linksArrayMembersToTheirMaster() throws Exception {
    ParsedWorkbook parsed = sample("poi/MatrixFormulaEvalTestData.xlsx");
    ParsedFormula master = parsed.formulas(0).get(at("H2"));
    assertEquals(ParsedFormula.Kind.ARRAY_MASTER, master.kind());
    assertEquals("H2:J4", master.arrayRange().orElseThrow());
    assertEquals(at("H2"), parsed.arrayMaster(0, at("I3")).orElseThrow());
    assertFalse(parsed.formulas(0).containsKey(at("I3")));
    assertTrue(parsed.arrayMaster(0, at("H2")).isEmpty());
    assertTrue(parsed.arrayMaster(0, at("A1")).isEmpty());
  }
}
