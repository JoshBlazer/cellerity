package io.github.joshblazer.cellerity.io;

import io.github.joshblazer.cellerity.core.DateSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** The content of {@code xl/workbook.xml}. */
record WorkbookPart(
    List<SheetEntry> sheets,
    List<DefinedName> definedNames,
    DateSystem dateSystem,
    Optional<String> calcMode,
    boolean fullCalcOnLoad) {

  record SheetEntry(String name, String relationshipId, SheetState state) {}

  static WorkbookPart parse(XMLStreamReader reader) throws XMLStreamException, XlsxReadException {
    List<SheetEntry> sheets = new ArrayList<>();
    List<DefinedName> names = new ArrayList<>();
    DateSystem dateSystem = DateSystem.SYSTEM_1900;
    Optional<String> calcMode = Optional.empty();
    boolean fullCalcOnLoad = false;
    while (reader.hasNext()) {
      if (reader.next() != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      if (String.valueOf(reader.getNamespaceURI()).contains("/office/excel/2006/")) {
        throw new XlsxReadException(
            XlsxReadException.Kind.UNSUPPORTED_FORMAT, "Pre-release Excel 2007 format");
      }
      switch (reader.getLocalName()) {
        case "workbookPr" -> {
          if (Xml.isTrue(reader.getAttributeValue(null, "date1904"))) {
            dateSystem = DateSystem.SYSTEM_1904;
          }
        }
        case "sheet" -> sheets.add(sheetEntry(reader));
        case "definedName" -> names.add(definedName(reader));
        case "calcPr" -> {
          calcMode = Optional.ofNullable(reader.getAttributeValue(null, "calcMode"));
          fullCalcOnLoad = Xml.isTrue(reader.getAttributeValue(null, "fullCalcOnLoad"));
        }
        default -> {}
      }
    }
    return new WorkbookPart(sheets, names, dateSystem, calcMode, fullCalcOnLoad);
  }

  private static SheetEntry sheetEntry(XMLStreamReader reader) throws XMLStreamException {
    String name = reader.getAttributeValue(null, "name");
    String id = relationshipId(reader);
    if (name == null || id == null) {
      throw new XMLStreamException("<sheet> needs a name and a relationship id");
    }
    SheetState state =
        switch (String.valueOf(reader.getAttributeValue(null, "state"))) {
          case "hidden" -> SheetState.HIDDEN;
          case "veryHidden" -> SheetState.VERY_HIDDEN;
          default -> SheetState.VISIBLE;
        };
    return new SheetEntry(name, id, state);
  }

  /** The {@code r:id} attribute, in either the Transitional or the Strict namespace. */
  private static String relationshipId(XMLStreamReader reader) {
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      String namespace = reader.getAttributeNamespace(i);
      if (reader.getAttributeLocalName(i).equals("id")
          && namespace != null
          && namespace.contains("relationships")) {
        return reader.getAttributeValue(i);
      }
    }
    return null;
  }

  private static DefinedName definedName(XMLStreamReader reader) throws XMLStreamException {
    String name = reader.getAttributeValue(null, "name");
    if (name == null) {
      throw new XMLStreamException("<definedName> needs a name");
    }
    String localSheetId = reader.getAttributeValue(null, "localSheetId");
    OptionalInt local =
        localSheetId == null ? OptionalInt.empty() : OptionalInt.of(Xml.parseInt(localSheetId));
    boolean hidden = Xml.isTrue(reader.getAttributeValue(null, "hidden"));
    return new DefinedName(name, reader.getElementText(), local, hidden);
  }

  WorkbookInfo toInfo(AppProperties app) {
    return new WorkbookInfo(app.application(), app.appVersion(), calcMode, fullCalcOnLoad);
  }
}
