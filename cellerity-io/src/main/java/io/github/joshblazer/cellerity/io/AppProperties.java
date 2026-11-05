package io.github.joshblazer.cellerity.io;

import java.util.Optional;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** The writer named by {@code docProps/app.xml}. */
record AppProperties(Optional<String> application, Optional<String> appVersion) {
  static final AppProperties NONE = new AppProperties(Optional.empty(), Optional.empty());

  static AppProperties parse(XMLStreamReader reader) throws XMLStreamException {
    Optional<String> application = Optional.empty();
    Optional<String> version = Optional.empty();
    while (reader.hasNext()) {
      if (reader.next() != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      switch (reader.getLocalName()) {
        case "Application" -> application = normalized(reader.getElementText());
        case "AppVersion" -> version = normalized(reader.getElementText());
        default -> {}
      }
    }
    return new AppProperties(application, version);
  }

  /** Collapses whitespace, since some writers wrap this text. Blank means not stated. */
  private static Optional<String> normalized(String text) {
    String clean = text.strip().replaceAll("\\s+", " ");
    return clean.isEmpty() ? Optional.empty() : Optional.of(clean);
  }
}
