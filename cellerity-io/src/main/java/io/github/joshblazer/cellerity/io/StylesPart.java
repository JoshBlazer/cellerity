package io.github.joshblazer.cellerity.io;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Which cell style indexes display dates. Styling never decides whether a workbook can load, so
 * unreadable numbers here count as "not a date".
 */
final class StylesPart {
  static final StylesPart NONE = new StylesPart(new BitSet());

  private final BitSet dateStyles;

  private StylesPart(BitSet dateStyles) {
    this.dateStyles = dateStyles;
  }

  static StylesPart parse(XMLStreamReader reader) throws XMLStreamException {
    Map<Integer, String> customFormats = new HashMap<>();
    List<Integer> formatIds = new ArrayList<>();
    boolean inNumFmts = false;
    boolean inCellXfs = false;
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        switch (reader.getLocalName()) {
          case "numFmts" -> inNumFmts = true;
          case "cellXfs" -> inCellXfs = true;
          case "numFmt" -> {
            int id = formatId(reader);
            String code = reader.getAttributeValue(null, "formatCode");
            if (inNumFmts && id >= 0 && code != null) {
              customFormats.put(id, code);
            }
          }
          case "xf" -> {
            if (inCellXfs) {
              formatIds.add(formatId(reader));
            }
          }
          default -> {}
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        switch (reader.getLocalName()) {
          case "numFmts" -> inNumFmts = false;
          case "cellXfs" -> inCellXfs = false;
          default -> {}
        }
      }
    }
    BitSet dates = new BitSet();
    for (int style = 0; style < formatIds.size(); style++) {
      int id = formatIds.get(style);
      String custom = customFormats.get(id);
      if (custom != null ? DateFormats.isDateCode(custom) : DateFormats.isBuiltInDate(id)) {
        dates.set(style);
      }
    }
    return new StylesPart(dates);
  }

  private static int formatId(XMLStreamReader reader) {
    String id = reader.getAttributeValue(null, "numFmtId");
    if (id == null) {
      return 0;
    }
    try {
      return Integer.parseInt(id.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  boolean isDate(int styleIndex) {
    return styleIndex >= 0 && dateStyles.get(styleIndex);
  }
}
