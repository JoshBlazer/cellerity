package io.github.joshblazer.cellerity.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class XlsxReaderTest {
  @TempDir Path dir;

  private Path file() {
    return dir.resolve("test.xlsx");
  }

  private Workbook read(XlsxFixture fixture) throws Exception {
    return XlsxReader.read(fixture.write(file()));
  }

  private static XlsxFixture one(String sheetData) {
    return new XlsxFixture().sheet("Sheet1", sheetData);
  }

  private static Cell cell(Workbook wb, String a1) {
    return wb.sheets().get(0).cell(CellRef.parse(a1).orElseThrow()).orElseThrow();
  }

  private static Value value(Workbook wb, String a1) {
    return cell(wb, a1).value().orElseThrow();
  }

  // Sheets

  @Test
  void listsSheetsInOrderWithState() throws Exception {
    Workbook wb =
        read(
            new XlsxFixture()
                .sheet("First", "")
                .sheetWithAttributes("Second", "state=\"hidden\"", "")
                .sheetWithAttributes("Third", "state=\"veryHidden\"", ""));
    assertEquals(
        List.of("First", "Second", "Third"), wb.sheets().stream().map(Sheet::name).toList());
    assertEquals(
        List.of(SheetState.VISIBLE, SheetState.HIDDEN, SheetState.VERY_HIDDEN),
        wb.sheets().stream().map(Sheet::state).toList());
    assertEquals(SheetKind.WORKSHEET, wb.sheets().get(0).kind());
  }

  @Test
  void findsSheetsIgnoringCase() throws Exception {
    Workbook wb = read(new XlsxFixture().sheet("Rates &amp; Terms", ""));
    assertEquals("Rates & Terms", wb.sheet("RATES & terms").orElseThrow().name());
    assertTrue(wb.sheet("Nope").isEmpty());
  }

  // Cached values

  @Test
  void readsEveryValueType() throws Exception {
    Workbook wb =
        read(
            one("<row r=\"1\">"
                    + "<c r=\"A1\"><v>1.5</v></c>"
                    + "<c r=\"B1\" t=\"n\"><v>-2E3</v></c>"
                    + "<c r=\"C1\" t=\"s\"><v>0</v></c>"
                    + "<c r=\"D1\" t=\"b\"><v>1</v></c>"
                    + "<c r=\"E1\" t=\"b\"><v>0</v></c>"
                    + "<c r=\"F1\" t=\"e\"><v>#N/A</v></c>"
                    + "<c r=\"G1\" t=\"str\"><f>\"x\"</f><v>x</v></c>"
                    + "<c r=\"H1\" t=\"inlineStr\"><is><t>inline</t></is></c>"
                    + "</row>")
                .sharedStrings("<si><t>shared</t></si>"));
    assertEquals(new Value.Num(1.5), value(wb, "A1"));
    assertEquals(new Value.Num(-2000), value(wb, "B1"));
    assertEquals(new Value.Text("shared"), value(wb, "C1"));
    assertEquals(new Value.Bool(true), value(wb, "D1"));
    assertEquals(new Value.Bool(false), value(wb, "E1"));
    assertEquals(new Value.Err(ExcelError.NA), value(wb, "F1"));
    assertEquals(new Value.Text("x"), value(wb, "G1"));
    assertEquals(new Value.Text("inline"), value(wb, "H1"));
  }

  @Test
  void preservesFullDoublePrecision() throws Exception {
    Workbook wb = read(one("<row r=\"1\"><c r=\"A1\"><v>0.30000000000000004</v></c></row>"));
    assertEquals(new Value.Num(0.1 + 0.2), value(wb, "A1"));
  }

  @Test
  void richSharedStringsConcatenateRunsAndSkipPhonetics() throws Exception {
    Workbook wb =
        read(
            one("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>")
                .sharedStrings(
                    "<si><r><t>Hello </t></r><r><rPr><b/></rPr><t>World</t></r>"
                        + "<rPh sb=\"0\" eb=\"1\"><t>IGNORED</t></rPh></si>"));
    assertEquals(new Value.Text("Hello World"), value(wb, "A1"));
  }

  @Test
  void richInlineStringsConcatenateRuns() throws Exception {
    Workbook wb =
        read(
            one(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><r><t>a</t></r><r><t>b</t></r></is></c></row>"));
    assertEquals(new Value.Text("ab"), value(wb, "A1"));
  }

  @Test
  void decodesXmlStringEscapes() throws Exception {
    Workbook wb =
        read(
            one("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>")
                .sharedStrings(
                    "<si><t>line_x000D_break</t></si><si><t>literal _x005F_x0041_ text</t></si>"));
    assertEquals(new Value.Text("line\rbreak"), value(wb, "A1"));
    assertEquals(new Value.Text("literal _x0041_ text"), value(wb, "B1"));
  }

  @Test
  void keepsSignificantWhitespace() throws Exception {
    Workbook wb =
        read(
            one("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>")
                .sharedStrings("<si><t xml:space=\"preserve\">  spaced  </t></si>"));
    assertEquals(new Value.Text("  spaced  "), value(wb, "A1"));
  }

  @Test
  void dropsCellsWithOnlyStyling() throws Exception {
    Workbook wb = read(one("<row r=\"1\"><c r=\"A1\" s=\"1\"/><c r=\"B1\"><v>1</v></c></row>"));
    assertEquals(1, wb.sheets().get(0).cells().size());
    assertTrue(wb.sheets().get(0).cell(new CellRef(1, 1)).isEmpty());
  }

  @Test
  void isoDateCellsBecomeSerials() throws Exception {
    Workbook wb =
        read(one("<row r=\"1\"><c r=\"A1\" t=\"d\"><v>2000-01-01T12:00:00</v></c></row>"));
    assertEquals(new Value.Num(36526.5), value(wb, "A1"));
    assertTrue(cell(wb, "A1").dateFormatted());
  }

  @ParameterizedTest
  @CsvSource({"n,abc", "n,NaN", "n,Infinity", "n,0x10", "n,1d", "b,2", "e,#NOPE!", "s,99", "s,x"})
  void rejectsInvalidCellContent(String type, String content) {
    XlsxReadException e =
        assertThrows(
            XlsxReadException.class,
            () ->
                read(
                    one("<row r=\"1\"><c r=\"A1\" t=\""
                            + type
                            + "\"><v>"
                            + content
                            + "</v></c></row>")
                        .sharedStrings("<si><t>only</t></si>")));
    assertEquals(XlsxReadException.Kind.MALFORMED, e.kind());
  }

  // Positions

  @Test
  void missingPositionAttributesContinueFromThePreviousCell() throws Exception {
    Workbook wb =
        read(
            one(
                "<row><c><v>1</v></c><c><v>2</v></c></row>"
                    + "<row r=\"5\"><c r=\"C5\"><v>3</v></c><c><v>4</v></c></row>"
                    + "<row><c><v>5</v></c></row>"));
    assertEquals(new Value.Num(1), value(wb, "A1"));
    assertEquals(new Value.Num(2), value(wb, "B1"));
    assertEquals(new Value.Num(3), value(wb, "C5"));
    assertEquals(new Value.Num(4), value(wb, "D5"));
    assertEquals(new Value.Num(5), value(wb, "A6"));
  }

  @Test
  void rejectsInvalidCellReferences() {
    XlsxReadException e =
        assertThrows(
            XlsxReadException.class,
            () -> read(one("<row r=\"1\"><c r=\"NOTACELL\"><v>1</v></c></row>")));
    assertEquals(XlsxReadException.Kind.MALFORMED, e.kind());
  }

  // Formulas

  @Test
  void readsNormalFormulaAndCachedValue() throws Exception {
    Cell c =
        cell(read(one("<row r=\"1\"><c r=\"A1\"><f>SUM(B1:C1)*2</f><v>10</v></c></row>")), "A1");
    Formula f = c.formula().orElseThrow();
    assertEquals(Formula.Kind.NORMAL, f.kind());
    assertEquals("SUM(B1:C1)*2", f.text().orElseThrow());
    assertEquals(new Value.Num(10), c.value().orElseThrow());
  }

  @Test
  void formulaWithoutCachedValueHasNoValue() throws Exception {
    Cell c = cell(read(one("<row r=\"1\"><c r=\"A1\"><f>1+1</f></c></row>")), "A1");
    assertTrue(c.value().isEmpty());
    assertTrue(c.formula().isPresent());
  }

  @Test
  void emptyCachedStringIsNotMissing() throws Exception {
    Cell c =
        cell(read(one("<row r=\"1\"><c r=\"A1\" t=\"str\"><f>\"\"</f><v></v></c></row>")), "A1");
    assertEquals(new Value.Text(""), c.value().orElseThrow());
  }

  @Test
  void readsSharedFormulaMasterAndFollower() throws Exception {
    Workbook wb =
        read(
            one(
                "<row r=\"1\"><c r=\"A1\"><f t=\"shared\" ref=\"A1:A2\" si=\"3\">B1*2</f><v>2</v></c></row>"
                    + "<row r=\"2\"><c r=\"A2\"><f t=\"shared\" si=\"3\"/><v>4</v></c></row>"));
    Formula master = cell(wb, "A1").formula().orElseThrow();
    assertEquals(Formula.Kind.SHARED, master.kind());
    assertEquals("B1*2", master.text().orElseThrow());
    assertEquals("A1:A2", master.ref().orElseThrow());
    assertEquals(OptionalInt.of(3), master.sharedIndex());
    Formula follower = cell(wb, "A2").formula().orElseThrow();
    assertEquals(Formula.Kind.SHARED, follower.kind());
    assertTrue(follower.text().isEmpty());
    assertEquals(OptionalInt.of(3), follower.sharedIndex());
    assertEquals(new Value.Num(4), value(wb, "A2"));
  }

  @Test
  void readsArrayAndDataTableFormulas() throws Exception {
    Workbook wb =
        read(
            one(
                "<row r=\"1\"><c r=\"A1\"><f t=\"array\" ref=\"A1:B1\">B2:C2*2</f><v>1</v></c>"
                    + "<c r=\"B1\"><f t=\"dataTable\" ref=\"B1:B3\" dt2D=\"0\" r1=\"A9\"/><v>2</v></c></row>"));
    Formula array = cell(wb, "A1").formula().orElseThrow();
    assertEquals(Formula.Kind.ARRAY, array.kind());
    assertEquals("B2:C2*2", array.text().orElseThrow());
    assertEquals("A1:B1", array.ref().orElseThrow());
    assertEquals(Formula.Kind.DATA_TABLE, cell(wb, "B1").formula().orElseThrow().kind());
  }

  @Test
  void decodesEscapesInFormulaText() throws Exception {
    Cell c =
        cell(
            read(one("<row r=\"1\"><c r=\"A1\"><f>A2&amp;\"a_x000D_b\"</f><v>1</v></c></row>")),
            "A1");
    assertEquals("A2&\"a\rb\"", c.formula().orElseThrow().text().orElseThrow());
  }

  // Defined names

  @Test
  void readsDefinedNames() throws Exception {
    Workbook wb =
        read(
            new XlsxFixture()
                .sheet("A", "")
                .sheet("B", "")
                .definedNames(
                    "<definedName name=\"Rate\">A!$B$1</definedName>"
                        + "<definedName name=\"Rate\" localSheetId=\"1\">B!$C$3</definedName>"
                        + "<definedName name=\"_xlnm.Print_Area\" localSheetId=\"0\" hidden=\"1\">A!$A$1</definedName>"));
    assertEquals(3, wb.definedNames().size());
    assertEquals("A!$B$1", wb.definedName("rate", OptionalInt.empty()).orElseThrow().refersTo());
    assertEquals("B!$C$3", wb.definedName("RATE", OptionalInt.of(1)).orElseThrow().refersTo());
    assertEquals("A!$B$1", wb.definedName("Rate", OptionalInt.of(0)).orElseThrow().refersTo());
    assertTrue(wb.definedName("_xlnm.Print_Area", OptionalInt.of(0)).orElseThrow().hidden());
  }

  // Date system and date formats

  @Test
  void detectsDateSystem() throws Exception {
    assertEquals(DateSystem.SYSTEM_1900, read(one("")).dateSystem());
    assertEquals(DateSystem.SYSTEM_1900, read(one("").workbookPr("date1904=\"0\"")).dateSystem());
    assertEquals(DateSystem.SYSTEM_1904, read(one("").workbookPr("date1904=\"1\"")).dateSystem());
    assertEquals(
        DateSystem.SYSTEM_1904, read(one("").workbookPr("date1904=\"true\"")).dateSystem());
  }

  @ParameterizedTest(name = "format code [{0}] is date: {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "yyyy-mm-dd|true",
        "h:mm:ss|true",
        "[h]:mm|true",
        "dd/mm/yy;@|true",
        "[$-409]mmmm d, yyyy|true",
        "0.00|false",
        "#,##0.00|false",
        "[Red]0.00|false",
        "General|false",
        "0.00E+00|false",
        "\"days: \"0|false",
        "\\d0|false",
        "_(* #,##0_)|false",
        "@|false",
      })
  void detectsCustomDateFormats(String code, boolean isDate) throws Exception {
    String escaped = code.replace("&", "&amp;").replace("\"", "&quot;");
    Workbook wb =
        read(
            one("<row r=\"1\"><c r=\"A1\" s=\"1\"><v>1</v></c></row>")
                .styles(
                    "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\""
                        + escaped
                        + "\"/></numFmts><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"164\"/></cellXfs>"));
    assertEquals(isDate, cell(wb, "A1").dateFormatted());
  }

  @ParameterizedTest(name = "built-in format id {0} is date: {1}")
  @CsvSource({
    "0,false",
    "2,false",
    "14,true",
    "15,true",
    "18,true",
    "22,true",
    "45,true",
    "47,true",
    "49,false"
  })
  void detectsBuiltInDateFormats(int id, boolean isDate) throws Exception {
    Workbook wb =
        read(
            one("<row r=\"1\"><c r=\"A1\" s=\"1\"><v>1</v></c></row>")
                .styles("<cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"" + id + "\"/></cellXfs>"));
    assertEquals(isDate, cell(wb, "A1").dateFormatted());
  }

  @Test
  void ignoresStyleFormatsOutsideCellXfs() throws Exception {
    Workbook wb =
        read(
            one("<row r=\"1\"><c r=\"A1\" s=\"0\"><v>1</v></c></row>")
                .styles(
                    "<cellStyleXfs><xf numFmtId=\"14\"/></cellStyleXfs>"
                        + "<cellXfs><xf numFmtId=\"0\"/></cellXfs>"));
    assertFalse(cell(wb, "A1").dateFormatted());
  }

  // Workbook information

  @Test
  void readsWriterAndCalculationSettings() throws Exception {
    WorkbookInfo info =
        read(one("")
                .appProperties("Microsoft Excel", "16.0300")
                .calcPr("calcMode=\"manual\" fullCalcOnLoad=\"1\""))
            .info();
    assertEquals("Microsoft Excel", info.application().orElseThrow());
    assertEquals("16.0300", info.appVersion().orElseThrow());
    assertEquals("manual", info.calcMode().orElseThrow());
    assertTrue(info.fullCalcOnLoad());
  }

  @Test
  void workbookInfoIsOptional() throws Exception {
    WorkbookInfo info = read(one("")).info();
    assertTrue(info.application().isEmpty());
    assertTrue(info.calcMode().isEmpty());
    assertFalse(info.fullCalcOnLoad());
  }

  // Package structure

  @Test
  void acceptsAbsoluteRelationshipTargets() throws Exception {
    XlsxFixture fixture =
        new XlsxFixture()
            .part(
                "xl/workbook.xml",
                "<workbook xmlns=\""
                    + XlsxFixture.MAIN
                    + "\" xmlns:r=\""
                    + XlsxFixture.REL
                    + "\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            .part(
                "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\""
                    + XlsxFixture.REL
                    + "/worksheet\" Target=\"/xl/worksheets/custom.xml\"/></Relationships>")
            .part(
                "xl/worksheets/custom.xml",
                "<worksheet xmlns=\""
                    + XlsxFixture.MAIN
                    + "\"><sheetData><row r=\"1\"><c r=\"A1\"><v>7</v></c></row></sheetData></worksheet>");
    assertEquals(new Value.Num(7), value(read(fixture), "A1"));
  }

  @Test
  void acceptsStrictOoxmlNamespaces() throws Exception {
    String strictMain = "http://purl.oclc.org/ooxml/spreadsheetml/main";
    String strictRel = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    String pkg = "http://schemas.openxmlformats.org/package/2006/relationships";
    XlsxFixture fixture =
        new XlsxFixture()
            .part(
                "_rels/.rels",
                "<Relationships xmlns=\""
                    + pkg
                    + "\"><Relationship Id=\"r\" Type=\""
                    + strictRel
                    + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            .part(
                "xl/workbook.xml",
                "<workbook xmlns=\""
                    + strictMain
                    + "\" xmlns:r=\""
                    + strictRel
                    + "\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            .part(
                "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns=\""
                    + pkg
                    + "\"><Relationship Id=\"rId1\" Type=\""
                    + strictRel
                    + "/worksheet\" Target=\"worksheets/s.xml\"/></Relationships>")
            .part(
                "xl/worksheets/s.xml",
                "<worksheet xmlns=\""
                    + strictMain
                    + "\"><sheetData><row r=\"1\"><c r=\"A1\"><v>8</v></c></row></sheetData></worksheet>");
    assertEquals(new Value.Num(8), value(read(fixture), "A1"));
  }

  @Test
  void nonWorksheetSheetsAreListedWithoutCells() throws Exception {
    XlsxFixture fixture =
        new XlsxFixture()
            .part(
                "xl/workbook.xml",
                "<workbook xmlns=\""
                    + XlsxFixture.MAIN
                    + "\" xmlns:r=\""
                    + XlsxFixture.REL
                    + "\"><sheets><sheet name=\"Chart\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            .part(
                "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\""
                    + XlsxFixture.REL
                    + "/chartsheet\" Target=\"chartsheets/c.xml\"/></Relationships>")
            .part("xl/chartsheets/c.xml", "<chartsheet/>");
    Sheet sheet = read(fixture).sheets().get(0);
    assertEquals(SheetKind.CHARTSHEET, sheet.kind());
    assertTrue(sheet.cells().isEmpty());
  }

  @Test
  void cellLikeElementsOutsideSheetDataAreIgnored() throws Exception {
    XlsxFixture fixture =
        one("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>")
            .part(
                "xl/worksheets/sheet1.xml",
                "<worksheet xmlns=\""
                    + XlsxFixture.MAIN
                    + "\"><extLst><ext><row r=\"9\"><c r=\"A9\"><v>9</v></c></row></ext></extLst>"
                    + "<sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>");
    assertEquals(1, read(fixture).sheets().get(0).cells().size());
  }

  // Failure modes

  private XlsxReadException.Kind failureKind(Path path) {
    return assertThrows(XlsxReadException.class, () -> XlsxReader.read(path)).kind();
  }

  @Test
  void missingFileIsAnIoFailure() {
    assertEquals(XlsxReadException.Kind.IO, failureKind(dir.resolve("absent.xlsx")));
  }

  @Test
  void legacyXlsIsUnsupported() throws IOException {
    Path path = dir.resolve("legacy.xls");
    Files.write(
        path,
        new byte[] {
          (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
        });
    assertEquals(XlsxReadException.Kind.UNSUPPORTED_FORMAT, failureKind(path));
  }

  @Test
  void nonZipFileIsUnsupported() throws IOException {
    Path path = dir.resolve("notes.xlsx");
    Files.writeString(path, "this is not a workbook");
    assertEquals(XlsxReadException.Kind.UNSUPPORTED_FORMAT, failureKind(path));
  }

  @Test
  void emptyFileIsUnsupported() throws IOException {
    Path path = dir.resolve("empty.xlsx");
    Files.write(path, new byte[0]);
    assertEquals(XlsxReadException.Kind.UNSUPPORTED_FORMAT, failureKind(path));
  }

  @Test
  void xlsbIsUnsupported() throws IOException {
    XlsxFixture fixture =
        new XlsxFixture()
            .part(
                "_rels/.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"r\" Type=\""
                    + XlsxFixture.REL
                    + "/officeDocument\" Target=\"xl/workbook.bin\"/></Relationships>");
    assertEquals(XlsxReadException.Kind.UNSUPPORTED_FORMAT, failureKind(fixture.write(file())));
  }

  @Test
  void zipWithoutWorkbookIsUnsupported() throws IOException {
    Path path = dir.resolve("other.zip");
    new XlsxFixture()
        .part("_rels/.rels", "<Relationships xmlns=\"x\"/>")
        .part("a.txt", "x")
        .write(path);
    Path noWorkbook = dir.resolve("x.xlsx");
    try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(noWorkbook))) {
      out.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
      out.closeEntry();
    }
    assertEquals(XlsxReadException.Kind.UNSUPPORTED_FORMAT, failureKind(noWorkbook));
  }

  @Test
  void malformedXmlIsMalformed() throws IOException {
    XlsxFixture fixture = one("").part("xl/worksheets/sheet1.xml", "<worksheet><sheetData><row>");
    assertEquals(XlsxReadException.Kind.MALFORMED, failureKind(fixture.write(file())));
  }

  @Test
  void missingSheetPartIsMalformed() throws IOException {
    XlsxFixture fixture =
        new XlsxFixture()
            .part(
                "xl/workbook.xml",
                "<workbook xmlns=\""
                    + XlsxFixture.MAIN
                    + "\" xmlns:r=\""
                    + XlsxFixture.REL
                    + "\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rIdMissing\"/></sheets></workbook>");
    assertEquals(XlsxReadException.Kind.MALFORMED, failureKind(fixture.write(file())));
  }

  // Hostile input

  @Test
  void billionLaughsIsRejected() throws IOException {
    String bomb =
        "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY a \"aaaaaaaaaa\">"
            + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">"
            + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]>"
            + "<sst xmlns=\""
            + XlsxFixture.MAIN
            + "\"><si><t>&c;</t></si></sst>";
    XlsxFixture fixture = one("").sharedStrings("").part("xl/sharedStrings.xml", bomb);
    assertEquals(XlsxReadException.Kind.MALFORMED, failureKind(fixture.write(file())));
  }

  @Test
  void externalEntitiesAreNeverResolved() throws IOException {
    Path secret = dir.resolve("secret.txt");
    Files.writeString(secret, "SECRET");
    String xxe =
        "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \""
            + secret.toUri()
            + "\">]><sst xmlns=\""
            + XlsxFixture.MAIN
            + "\"><si><t>&e;</t></si></sst>";
    XlsxFixture fixture =
        one("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>")
            .sharedStrings("")
            .part("xl/sharedStrings.xml", xxe);
    assertEquals(XlsxReadException.Kind.MALFORMED, failureKind(fixture.write(file())));
  }

  @Test
  void partSizeLimitIsEnforcedOnActualBytes() throws IOException {
    Path path = one("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>").write(file());
    XlsxReadException e =
        assertThrows(
            XlsxReadException.class,
            () -> XlsxReader.read(path, new ReadLimits(50, 1_000_000, 1_000)));
    assertEquals(XlsxReadException.Kind.LIMIT_EXCEEDED, e.kind());
  }

  @Test
  void totalSizeLimitIsEnforced() throws IOException {
    Path path = one("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>").write(file());
    XlsxReadException e =
        assertThrows(
            XlsxReadException.class,
            () -> XlsxReader.read(path, new ReadLimits(1_000_000, 100, 1_000)));
    assertEquals(XlsxReadException.Kind.LIMIT_EXCEEDED, e.kind());
  }

  @Test
  void cellLimitIsEnforced() throws IOException {
    Path path =
        one("<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\"><v>2</v></c></row>").write(file());
    XlsxReadException e =
        assertThrows(
            XlsxReadException.class,
            () -> XlsxReader.read(path, new ReadLimits(1_000_000, 1_000_000, 1)));
    assertEquals(XlsxReadException.Kind.LIMIT_EXCEEDED, e.kind());
  }

  // Real sample

  @Test
  void readsTheCommittedSyntheticSample() throws Exception {
    Workbook wb = XlsxReader.read(Path.of("../corpus/samples/synthetic/sample.xlsx"));
    assertEquals(
        List.of("Sheet1", "Rates & Terms"), wb.sheets().stream().map(Sheet::name).toList());
    assertEquals(new Value.Num(42), value(wb, "A1"));
    assertEquals(
        "B1*'Rates & Terms'!B1", cell(wb, "B2").formula().orElseThrow().text().orElseThrow());
    assertEquals(new Value.Err(ExcelError.DIV0), value(wb, "B3"));
    assertTrue(cell(wb, "B4").dateFormatted());
    assertEquals("Cellerity MakeSample (synthetic)", wb.info().application().orElseThrow());
  }
}
