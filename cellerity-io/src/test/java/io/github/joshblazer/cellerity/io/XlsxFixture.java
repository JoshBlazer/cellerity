package io.github.joshblazer.cellerity.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small hand-written workbooks. Defaults are the minimum a valid package needs. */
final class XlsxFixture {
  static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String PKG = "http://schemas.openxmlformats.org/package/2006/relationships";

  private final Map<String, String> parts = new LinkedHashMap<>();
  private final StringBuilder sheetsXml = new StringBuilder();
  private final StringBuilder relsXml = new StringBuilder();
  private String workbookExtra = "";
  private String definedNames = "";
  private String calcPr = "";
  private int sheetCount;

  /** Adds a worksheet whose {@code sheetData} contains the given XML. */
  XlsxFixture sheet(String name, String sheetData) {
    return sheetWithAttributes(name, "", sheetData);
  }

  XlsxFixture sheetWithAttributes(String name, String attributes, String sheetData) {
    sheetCount++;
    String rId = "rIdS" + sheetCount;
    String part = "worksheets/sheet" + sheetCount + ".xml";
    sheetsXml
        .append("<sheet name=\"")
        .append(name)
        .append("\" sheetId=\"")
        .append(sheetCount)
        .append("\" r:id=\"")
        .append(rId)
        .append("\" ")
        .append(attributes)
        .append("/>");
    relsXml.append(rel(rId, REL + "/worksheet", part));
    parts.put(
        "xl/" + part,
        "<worksheet xmlns=\"" + MAIN + "\"><sheetData>" + sheetData + "</sheetData></worksheet>");
    return this;
  }

  XlsxFixture sharedStrings(String innerXml) {
    relsXml.append(rel("rIdSst", REL + "/sharedStrings", "sharedStrings.xml"));
    parts.put("xl/sharedStrings.xml", "<sst xmlns=\"" + MAIN + "\">" + innerXml + "</sst>");
    return this;
  }

  XlsxFixture styles(String innerXml) {
    relsXml.append(rel("rIdStyles", REL + "/styles", "styles.xml"));
    parts.put("xl/styles.xml", "<styleSheet xmlns=\"" + MAIN + "\">" + innerXml + "</styleSheet>");
    return this;
  }

  XlsxFixture definedNames(String innerXml) {
    definedNames = "<definedNames>" + innerXml + "</definedNames>";
    return this;
  }

  XlsxFixture workbookPr(String attributes) {
    workbookExtra = "<workbookPr " + attributes + "/>";
    return this;
  }

  XlsxFixture calcPr(String attributes) {
    calcPr = "<calcPr " + attributes + "/>";
    return this;
  }

  XlsxFixture appProperties(String application, String version) {
    parts.put(
        "docProps/app.xml",
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/"
            + "extended-properties\"><Application>"
            + application
            + "</Application><AppVersion>"
            + version
            + "</AppVersion></Properties>");
    return this;
  }

  /** Adds or replaces any part. The replacement wins over generated content. */
  XlsxFixture part(String name, String xml) {
    parts.put(name, xml);
    return this;
  }

  private static String rel(String id, String type, String target) {
    return "<Relationship Id=\"" + id + "\" Type=\"" + type + "\" Target=\"" + target + "\"/>";
  }

  Path write(Path file) throws IOException {
    Map<String, String> all = new LinkedHashMap<>();
    all.put(
        "_rels/.rels",
        "<Relationships xmlns=\""
            + PKG
            + "\">"
            + rel("rId1", REL + "/officeDocument", "xl/workbook.xml")
            + "</Relationships>");
    all.put(
        "xl/workbook.xml",
        "<workbook xmlns=\""
            + MAIN
            + "\" xmlns:r=\""
            + REL
            + "\">"
            + workbookExtra
            + "<sheets>"
            + sheetsXml
            + "</sheets>"
            + definedNames
            + calcPr
            + "</workbook>");
    all.put(
        "xl/_rels/workbook.xml.rels",
        "<Relationships xmlns=\"" + PKG + "\">" + relsXml + "</Relationships>");
    all.putAll(parts);
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (var part : all.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }
}
