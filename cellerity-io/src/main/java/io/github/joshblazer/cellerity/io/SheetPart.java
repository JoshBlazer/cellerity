package io.github.joshblazer.cellerity.io;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.Value;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Parses one worksheet part. Descends only into {@code sheetData}. */
final class SheetPart {
  private final XMLStreamReader reader;
  private final DateSystem dateSystem;
  private final List<String> sharedStrings;
  private final StylesPart styles;
  private final XlsxReader.CellBudget budget;
  private final Map<CellRef, Cell> cells = new LinkedHashMap<>();

  SheetPart(
      XMLStreamReader reader,
      DateSystem dateSystem,
      List<String> sharedStrings,
      StylesPart styles,
      XlsxReader.CellBudget budget) {
    this.reader = reader;
    this.dateSystem = dateSystem;
    this.sharedStrings = sharedStrings;
    this.styles = styles;
    this.budget = budget;
  }

  Map<CellRef, Cell> parse() throws XMLStreamException {
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT) {
        break;
      }
    }
    // Now on the root element. Only direct sheetData children matter.
    while (Xml.nextChild(reader)) {
      if (reader.getLocalName().equals("sheetData")) {
        parseSheetData();
      } else {
        Xml.skipElement(reader);
      }
    }
    return Collections.unmodifiableMap(cells);
  }

  private void parseSheetData() throws XMLStreamException {
    int rowNumber = 0;
    while (Xml.nextChild(reader)) {
      if (!reader.getLocalName().equals("row")) {
        Xml.skipElement(reader);
        continue;
      }
      String r = reader.getAttributeValue(null, "r");
      rowNumber = r == null ? rowNumber + 1 : Xml.parseInt(r);
      parseRow(rowNumber);
    }
  }

  private void parseRow(int rowNumber) throws XMLStreamException {
    int previousColumn = 0;
    while (Xml.nextChild(reader)) {
      if (!reader.getLocalName().equals("c")) {
        Xml.skipElement(reader);
        continue;
      }
      CellRef ref = cellPosition(rowNumber, previousColumn);
      previousColumn = ref.column();
      parseCell(ref);
    }
  }

  private CellRef cellPosition(int rowNumber, int previousColumn) throws XMLStreamException {
    String r = reader.getAttributeValue(null, "r");
    if (r != null) {
      return CellRef.parse(r)
          .orElseThrow(() -> new XMLStreamException("Invalid cell reference: " + r));
    }
    try {
      return new CellRef(previousColumn + 1, rowNumber);
    } catch (IllegalArgumentException e) {
      throw new XMLStreamException(e.getMessage(), e);
    }
  }

  private void parseCell(CellRef ref) throws XMLStreamException {
    String type = reader.getAttributeValue(null, "t");
    boolean dateFormatted = styles.isDate(styleIndex());
    String stored = null;
    String inline = null;
    Optional<Formula> formula = Optional.empty();
    while (Xml.nextChild(reader)) {
      switch (reader.getLocalName()) {
        case "v" -> stored = reader.getElementText();
        case "f" -> formula = Optional.of(parseFormula());
        case "is" -> inline = Xml.richText(reader);
        default -> Xml.skipElement(reader);
      }
    }
    Optional<Value> value = cellValue(ref, type, stored, inline);
    if (value.isEmpty() && formula.isEmpty()) {
      return;
    }
    budget.spendOne();
    cells.put(ref, new Cell(ref, value, formula, dateFormatted || "d".equals(type)));
  }

  private int styleIndex() {
    String s = reader.getAttributeValue(null, "s");
    if (s == null) {
      return 0;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private Formula parseFormula() throws XMLStreamException {
    Formula.Kind kind =
        switch (String.valueOf(reader.getAttributeValue(null, "t"))) {
          case "null", "normal" -> Formula.Kind.NORMAL;
          case "shared" -> Formula.Kind.SHARED;
          case "array" -> Formula.Kind.ARRAY;
          case "dataTable" -> Formula.Kind.DATA_TABLE;
          default -> throw new XMLStreamException("Unknown formula type");
        };
    String si = reader.getAttributeValue(null, "si");
    OptionalInt sharedIndex = si == null ? OptionalInt.empty() : OptionalInt.of(Xml.parseInt(si));
    Optional<String> range = Optional.ofNullable(reader.getAttributeValue(null, "ref"));
    String text = reader.getElementText();
    return new Formula(
        kind,
        text.isEmpty() ? Optional.empty() : Optional.of(Xml.decodeXString(text)),
        sharedIndex,
        range);
  }

  private Optional<Value> cellValue(CellRef ref, String type, String stored, String inline)
      throws XMLStreamException {
    if ("inlineStr".equals(type)) {
      return Optional.ofNullable(inline).map(Value.Text::new);
    }
    if (stored == null) {
      return Optional.empty();
    }
    String kind = type == null ? "n" : type;
    if (stored.isEmpty() && !kind.equals("str")) {
      return Optional.empty();
    }
    Value value =
        switch (kind) {
          case "n" -> new Value.Num(parseNumber(ref, stored));
          case "s" -> new Value.Text(sharedString(ref, stored));
          case "str" -> new Value.Text(Xml.decodeXString(stored));
          case "b" -> new Value.Bool(parseBoolean(ref, stored));
          case "e" ->
              new Value.Err(
                  ExcelError.fromCode(stored)
                      .orElseThrow(() -> invalid(ref, "unknown error " + stored)));
          case "d" -> new Value.Num(parseIsoDate(ref, stored));
          default -> throw invalid(ref, "unknown cell type " + kind);
        };
    return Optional.of(value);
  }

  /** Accepts plain decimal syntax only. {@code parseDouble} alone also takes NaN, hex, and 1d. */
  private static double parseNumber(CellRef ref, String text) throws XMLStreamException {
    String trimmed = text.trim();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      boolean allowed =
          (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
      if (!allowed) {
        throw invalid(ref, "invalid number " + text);
      }
    }
    try {
      double value = Double.parseDouble(trimmed);
      if (Double.isInfinite(value)) {
        throw invalid(ref, "number out of range " + text);
      }
      return value;
    } catch (NumberFormatException e) {
      throw invalid(ref, "invalid number " + text);
    }
  }

  private String sharedString(CellRef ref, String indexText) throws XMLStreamException {
    int index = Xml.parseInt(indexText);
    if (index < 0 || index >= sharedStrings.size()) {
      throw invalid(ref, "shared string index out of range: " + index);
    }
    return sharedStrings.get(index);
  }

  private static boolean parseBoolean(CellRef ref, String text) throws XMLStreamException {
    return switch (text.trim()) {
      case "1" -> true;
      case "0" -> false;
      default -> throw invalid(ref, "invalid boolean " + text);
    };
  }

  private double parseIsoDate(CellRef ref, String text) throws XMLStreamException {
    String trimmed = text.trim();
    try {
      LocalDateTime dateTime =
          trimmed.contains("T")
              ? LocalDateTime.parse(trimmed)
              : LocalDate.parse(trimmed).atStartOfDay();
      return dateSystem.toSerial(dateTime);
    } catch (DateTimeParseException | IllegalArgumentException e) {
      throw invalid(ref, "invalid date " + text);
    }
  }

  private static XMLStreamException invalid(CellRef ref, String problem) {
    return new XMLStreamException("Cell " + ref.toA1() + ": " + problem);
  }
}
