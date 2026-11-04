package io.github.joshblazer.cellerity.io;

import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Small helpers for walking StAX events. */
final class Xml {
  private static final Pattern ESCAPE = Pattern.compile("_x([0-9A-Fa-f]{4})_");

  private Xml() {}

  /**
   * Moves to the next child of the current element. Returns false, positioned on the parent's end
   * tag, when there are no more.
   */
  static boolean nextChild(XMLStreamReader reader) throws XMLStreamException {
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        return true;
      }
      if (event == XMLStreamConstants.END_ELEMENT) {
        return false;
      }
    }
    return false;
  }

  /** Skips the current element and everything inside it. */
  static void skipElement(XMLStreamReader reader) throws XMLStreamException {
    int depth = 1;
    while (depth > 0 && reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }
  }

  /**
   * Reads the text of an {@code si} or {@code is} element: its direct {@code t}, plus {@code t} in
   * each {@code r} run. Phonetic hints in {@code rPh} are not part of the value.
   */
  static String richText(XMLStreamReader reader) throws XMLStreamException {
    StringBuilder text = new StringBuilder();
    while (nextChild(reader)) {
      switch (reader.getLocalName()) {
        case "t" -> text.append(reader.getElementText());
        case "r" -> {
          while (nextChild(reader)) {
            if (reader.getLocalName().equals("t")) {
              text.append(reader.getElementText());
            } else {
              skipElement(reader);
            }
          }
        }
        default -> skipElement(reader);
      }
    }
    return decodeXString(text.toString());
  }

  /** Decodes {@code ST_Xstring} escapes such as {@code _x000D_}. */
  static String decodeXString(String text) {
    if (text.indexOf("_x") < 0) {
      return text;
    }
    return ESCAPE
        .matcher(text)
        .replaceAll(match -> {
          char decoded = (char) Integer.parseInt(match.group(1), 16);
          return java.util.regex.Matcher.quoteReplacement(String.valueOf(decoded));
        });
  }

  /** XSD booleans. A missing attribute is false. */
  static boolean isTrue(String attribute) {
    return "1".equals(attribute) || "true".equals(attribute) || "on".equals(attribute);
  }

  static int parseInt(String text) throws XMLStreamException {
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      throw new XMLStreamException("Invalid integer: " + text, e);
    }
  }
}
