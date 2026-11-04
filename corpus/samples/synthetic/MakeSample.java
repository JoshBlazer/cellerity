import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates sample.xlsx byte-for-byte reproducibly.
 *
 * <p>Lower-trust: the cached values are written by hand, not computed by Excel. This file tests the
 * reader and demonstrates the CLI. It must never be used as fidelity ground truth.
 *
 * <p>Usage: {@code java MakeSample.java sample.xlsx}
 */
public final class MakeSample {
  private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private static final String REL =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

  public static void main(String[] args) throws IOException {
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put(
        "[Content_Types].xml",
        HEADER
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\""
            + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats"
            + "-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "</Types>");
    parts.put(
        "_rels/.rels",
        HEADER
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\""
            + REL
            + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\""
            + REL
            + "/extended-properties\" Target=\"docProps/app.xml\"/>"
            + "</Relationships>");
    parts.put(
        "docProps/app.xml",
        HEADER
            + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/"
            + "extended-properties\"><Application>Cellerity MakeSample (synthetic)</Application>"
            + "</Properties>");
    parts.put(
        "xl/workbook.xml",
        HEADER
            + "<workbook xmlns=\""
            + MAIN
            + "\" xmlns:r=\""
            + REL
            + "\"><sheets>"
            + "<sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/>"
            + "<sheet name=\"Rates &amp; Terms\" sheetId=\"2\" r:id=\"rId2\"/>"
            + "</sheets><definedNames>"
            + "<definedName name=\"CoverAmount\">Sheet1!$B$1</definedName>"
            + "<definedName name=\"AnnualRate\">'Rates &amp; Terms'!$B$1</definedName>"
            + "<definedName name=\"LocalNote\" localSheetId=\"0\">Sheet1!$A$4</definedName>"
            + "</definedNames><calcPr calcMode=\"auto\"/></workbook>");
    parts.put(
        "xl/_rels/workbook.xml.rels",
        HEADER
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\""
            + REL
            + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\""
            + REL
            + "/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\""
            + REL
            + "/sharedStrings\" Target=\"sharedStrings.xml\"/>"
            + "<Relationship Id=\"rId4\" Type=\""
            + REL
            + "/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>");
    parts.put(
        "xl/sharedStrings.xml",
        HEADER
            + "<sst xmlns=\""
            + MAIN
            + "\"><si><t>Premium model</t></si><si><t>Rate</t></si></sst>");
    parts.put(
        "xl/styles.xml",
        HEADER
            + "<styleSheet xmlns=\""
            + MAIN
            + "\"><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>");
    parts.put(
        "xl/worksheets/sheet1.xml",
        HEADER
            + "<worksheet xmlns=\""
            + MAIN
            + "\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\"><v>42</v></c><c r=\"B1\"><v>50000</v></c></row>"
            + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c>"
            + "<c r=\"B2\"><f>B1*'Rates &amp; Terms'!B1</f><v>1750</v></c></row>"
            + "<row r=\"3\"><c r=\"A3\" t=\"b\"><v>1</v></c>"
            + "<c r=\"B3\" t=\"e\"><f>1/0</f><v>#DIV/0!</v></c></row>"
            + "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>inline note</t></is></c>"
            + "<c r=\"B4\" s=\"1\"><v>46329</v></c></row>"
            + "</sheetData></worksheet>");
    parts.put(
        "xl/worksheets/sheet2.xml",
        HEADER
            + "<worksheet xmlns=\""
            + MAIN
            + "\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>1</v></c><c r=\"B1\"><v>0.035</v></c></row>"
            + "</sheetData></worksheet>");

    try (OutputStream file = Files.newOutputStream(Path.of(args[0]));
        ZipOutputStream zip = new ZipOutputStream(file)) {
      for (var part : parts.entrySet()) {
        ZipEntry entry = new ZipEntry(part.getKey());
        entry.setTimeLocal(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
  }

  private MakeSample() {}
}
