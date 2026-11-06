package io.github.joshblazer.cellerity.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CycleReportTest {
  @TempDir Path dir;

  private static final Map<String, String> PARTS =
      Map.of(
          "[Content_Types].xml",
          "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
              + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package"
              + ".relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
              + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats"
              + "-officedocument.spreadsheetml.sheet.main+xml\"/>"
              + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd"
              + ".openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>",
          "_rels/.rels",
          "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument"
              + "/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
          "xl/workbook.xml",
          "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r="
              + "\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
              + "<sheet name=\"Loop\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
          "xl/_rels/workbook.xml.rels",
          "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument"
              + "/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
          "xl/worksheets/sheet1.xml",
          "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
              + "<row r=\"1\"><c r=\"A1\"><f>B1+1</f><v>0</v></c><c r=\"B1\"><f>A1+1</f><v>0</v></c>"
              + "</row></sheetData></worksheet>");

  @Test
  void verifyNamesTheCellsInACycleAndExitsOne() throws IOException {
    Path file = dir.resolve("loop.xlsx");
    try (OutputStream raw = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(raw)) {
      for (Map.Entry<String, String> part : PARTS.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            new String[] {"verify", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    String printed = out.toString(StandardCharsets.UTF_8);
    assertEquals(1, exit, err.toString(StandardCharsets.UTF_8));
    assertTrue(printed.contains("possible cycles: 1"), printed);
    assertTrue(printed.contains("Loop!A1 -> Loop!B1"), printed);
    assertTrue(printed.contains("feature cycle: 2"), printed);
  }
}
