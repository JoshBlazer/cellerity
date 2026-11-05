package io.github.joshblazer.cellerity.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Files whose writer label says Excel. Expected values were read from the files themselves. */
class PoiSamplesTest {
  private static Workbook sample(String name) throws XlsxReadException {
    return XlsxReader.read(Path.of("../corpus/samples/poi", name));
  }

  private static Cell cell(Workbook workbook, String sheet, String a1) {
    return workbook.sheet(sheet).orElseThrow().cell(CellRef.parse(a1).orElseThrow()).orElseThrow();
  }

  @Test
  void sharedFormulasKeepMasterTextAndFollowerIndex() throws Exception {
    Workbook workbook = sample("shared_formulas.xlsx");
    assertEquals("Microsoft Excel", workbook.info().application().orElseThrow());
    assertEquals(DateSystem.SYSTEM_1900, workbook.dateSystem());
    assertEquals(63, workbook.definedNames().size());

    Cell plain = cell(workbook, "Label", "A2");
    assertEquals("B2", plain.formula().orElseThrow().text().orElseThrow());
    assertEquals(new Value.Text("ProductionOrderConfirmation"), plain.value().orElseThrow());

    Formula follower = cell(workbook, "Label", "A3").formula().orElseThrow();
    assertEquals(Formula.Kind.SHARED, follower.kind());
    assertTrue(follower.text().isEmpty());
    assertEquals(OptionalInt.of(0), follower.sharedIndex());
    assertEquals(
        new Value.Text("RequiredAcceptanceDate"),
        cell(workbook, "Label", "A3").value().orElseThrow());
  }

  @Test
  void date1904WorkbookFromMacExcel() throws Exception {
    Workbook workbook = sample("DateFormatTests.xlsx");
    assertEquals(DateSystem.SYSTEM_1904, workbook.dateSystem());
    assertEquals("Microsoft Macintosh Excel", workbook.info().application().orElseThrow());
    Cell cell = cell(workbook, "Tests", "A2");
    assertEquals("TEXT(C2, B2)", cell.formula().orElseThrow().text().orElseThrow());
    assertEquals(new Value.Text("11-10-52"), cell.value().orElseThrow());
  }

  @Test
  void cachedErrorValuesFromExcel() throws Exception {
    Workbook workbook = sample("VLookupFullColumn.xlsx");
    Cell found = cell(workbook, "Sheet1", "B4");
    assertEquals("VLOOKUP(A4,$D:$E,2,0)", found.formula().orElseThrow().text().orElseThrow());
    assertEquals(new Value.Text("Value1"), found.value().orElseThrow());
    assertEquals(
        new Value.Err(ExcelError.NA), cell(workbook, "Sheet1", "B5").value().orElseThrow());
  }

  @Test
  void arrayFormulaStoresTextOnTheMasterAndValuesOnMembers() throws Exception {
    Workbook workbook = sample("MatrixFormulaEvalTestData.xlsx");
    Formula master = cell(workbook, "Sheet1", "H2").formula().orElseThrow();
    assertEquals(Formula.Kind.ARRAY, master.kind());
    assertEquals("B2:D4 + E2:G4", master.text().orElseThrow());
    assertEquals("H2:J4", master.ref().orElseThrow());

    Cell member = cell(workbook, "Sheet1", "I2");
    assertTrue(member.formula().isEmpty());
    assertTrue(member.value().isPresent());
  }
}
