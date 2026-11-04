package io.github.joshblazer.cellerity.io;

import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Reads {@code xl/sharedStrings.xml}. */
final class SharedStringsPart {
  private SharedStringsPart() {}

  static List<String> parse(XMLStreamReader reader) throws XMLStreamException {
    List<String> strings = new ArrayList<>();
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("si")) {
        strings.add(Xml.richText(reader));
      }
    }
    return strings;
  }
}
