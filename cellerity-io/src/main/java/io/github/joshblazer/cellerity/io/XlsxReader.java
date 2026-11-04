package io.github.joshblazer.cellerity.io;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.xml.stream.XMLStreamException;

/** Reads {@code .xlsx} and {@code .xlsm} files. Thread-safe: holds no state between reads. */
public final class XlsxReader {
  private static final String OFFICE_DOCUMENT = "/officeDocument";
  private static final String DEFAULT_WORKBOOK_PART = "xl/workbook.xml";
  private static final byte[] OLE2_MAGIC = {
    (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
  };

  private XlsxReader() {}

  /** Reads a workbook with {@link ReadLimits#DEFAULT}. */
  public static Workbook read(Path path) throws XlsxReadException {
    return read(path, ReadLimits.DEFAULT);
  }

  /** Reads a workbook. Every expected failure is an {@link XlsxReadException}. */
  public static Workbook read(Path path, ReadLimits limits) throws XlsxReadException {
    sniff(path);
    try (OpcPackage pkg = OpcPackage.open(path, limits)) {
      return readPackage(pkg, limits);
    } catch (IOException e) {
      throw new XlsxReadException(
          XlsxReadException.Kind.IO, "Could not close the file: " + e.getMessage(), e);
    }
  }

  private static void sniff(Path path) throws XlsxReadException {
    byte[] head;
    try (InputStream in = Files.newInputStream(path)) {
      head = in.readNBytes(OLE2_MAGIC.length);
    } catch (NoSuchFileException e) {
      throw new XlsxReadException(XlsxReadException.Kind.IO, "File not found", e);
    } catch (IOException e) {
      throw new XlsxReadException(
          XlsxReadException.Kind.IO, "Could not read the file: " + e.getMessage(), e);
    }
    if (java.util.Arrays.equals(head, OLE2_MAGIC)) {
      throw new XlsxReadException(
          XlsxReadException.Kind.UNSUPPORTED_FORMAT,
          "OLE2 container: a legacy .xls file or an encrypted workbook");
    }
    if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
      throw new XlsxReadException(XlsxReadException.Kind.UNSUPPORTED_FORMAT, "Not a zip package");
    }
  }

  private static Workbook readPackage(OpcPackage pkg, ReadLimits limits) throws XlsxReadException {
    Relationships packageRels = pkg.relationships("");
    String workbookPart = findWorkbookPart(pkg, packageRels);
    Relationships workbookRels = pkg.relationships(workbookPart);

    WorkbookPart workbook = pkg.parse(workbookPart, WorkbookPart::parse);

    List<String> sharedStrings =
        optionalPart(pkg, workbookRels, "/sharedStrings", SharedStringsPart::parse)
            .orElse(List.of());
    StylesPart styles =
        optionalPart(pkg, workbookRels, "/styles", StylesPart::parse).orElse(StylesPart.NONE);
    WorkbookInfo info = workbook.toInfo(readAppProperties(pkg, packageRels));

    CellBudget budget = new CellBudget(limits.maxCells());
    List<Sheet> sheets = new ArrayList<>();
    for (WorkbookPart.SheetEntry entry : workbook.sheets()) {
      sheets.add(
          readSheet(
              pkg, workbookRels, entry, workbook.dateSystem(), sharedStrings, styles, budget));
    }
    return new Workbook(sheets, workbook.definedNames(), workbook.dateSystem(), info);
  }

  private static String findWorkbookPart(OpcPackage pkg, Relationships packageRels)
      throws XlsxReadException {
    Optional<String> declared = packageRels.targetOfType(OFFICE_DOCUMENT);
    String part = declared.orElse(DEFAULT_WORKBOOK_PART);
    if (part.toLowerCase(Locale.ROOT).endsWith(".bin")) {
      throw new XlsxReadException(
          XlsxReadException.Kind.UNSUPPORTED_FORMAT, "Binary .xlsb workbook");
    }
    if (!pkg.contains(part)) {
      throw new XlsxReadException(
          XlsxReadException.Kind.UNSUPPORTED_FORMAT, "Zip package has no workbook part: " + part);
    }
    return part;
  }

  private static <T> Optional<T> optionalPart(
      OpcPackage pkg, Relationships rels, String typeSuffix, OpcPackage.PartParser<T> parser)
      throws XlsxReadException {
    Optional<String> part = rels.targetOfType(typeSuffix);
    if (part.isEmpty()) {
      return Optional.empty();
    }
    if (!pkg.contains(part.get())) {
      throw new XlsxReadException(
          XlsxReadException.Kind.MALFORMED, "Declared part is missing: " + part.get());
    }
    return Optional.of(pkg.parse(part.get(), parser));
  }

  private static AppProperties readAppProperties(OpcPackage pkg, Relationships packageRels)
      throws XlsxReadException {
    String part = packageRels.targetOfType("/extended-properties").orElse("docProps/app.xml");
    return pkg.contains(part) ? pkg.parse(part, AppProperties::parse) : AppProperties.NONE;
  }

  private static Sheet readSheet(
      OpcPackage pkg,
      Relationships workbookRels,
      WorkbookPart.SheetEntry entry,
      DateSystem dateSystem,
      List<String> sharedStrings,
      StylesPart styles,
      CellBudget budget)
      throws XlsxReadException {
    Relationships.Relationship rel =
        workbookRels
            .byId(entry.relationshipId())
            .orElseThrow(() ->
                new XlsxReadException(
                    XlsxReadException.Kind.MALFORMED,
                    "Sheet '" + entry.name() + "' has no matching relationship"));
    SheetKind kind = kindOf(rel.type());
    if (kind != SheetKind.WORKSHEET) {
      return new Sheet(entry.name(), kind, entry.state(), Map.of());
    }
    if (!pkg.contains(rel.target())) {
      throw new XlsxReadException(
          XlsxReadException.Kind.MALFORMED, "Sheet part is missing: " + rel.target());
    }
    Map<CellRef, Cell> cells =
        pkg.parse(
            rel.target(),
            reader -> new SheetPart(reader, dateSystem, sharedStrings, styles, budget).parse());
    return new Sheet(entry.name(), kind, entry.state(), cells);
  }

  private static SheetKind kindOf(String type) {
    String lower = type.toLowerCase(Locale.ROOT);
    if (lower.endsWith("/worksheet")) {
      return SheetKind.WORKSHEET;
    }
    if (lower.endsWith("/chartsheet")) {
      return SheetKind.CHARTSHEET;
    }
    if (lower.endsWith("/dialogsheet")) {
      return SheetKind.DIALOGSHEET;
    }
    if (lower.endsWith("macrosheet")) {
      return SheetKind.MACROSHEET;
    }
    return SheetKind.OTHER;
  }

  /** Enforces {@link ReadLimits#maxCells()} across all sheets of one read. */
  static final class CellBudget {
    private final long max;
    private long used;

    CellBudget(long max) {
      this.max = max;
    }

    void spendOne() throws XMLStreamException {
      used++;
      if (used > max) {
        throw new LimitExceededXmlException("More than " + max + " cells");
      }
    }
  }

  /** Signals a limit from inside XML parsing, where only XML exceptions can be thrown. */
  static final class LimitExceededXmlException extends XMLStreamException {
    LimitExceededXmlException(String message) {
      super(message);
    }
  }
}
