package io.github.joshblazer.cellerity.io;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** One {@code .rels} part. Targets are resolved to part names. */
final class Relationships {
  static final Relationships EMPTY = new Relationships(List.of());

  record Relationship(String id, String type, String target) {}

  private final List<Relationship> all;

  private Relationships(List<Relationship> all) {
    this.all = all;
  }

  static Relationships parse(XMLStreamReader reader, String baseDirectory)
      throws XMLStreamException {
    List<Relationship> found = new ArrayList<>();
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT
          && reader.getLocalName().equals("Relationship")) {
        String id = reader.getAttributeValue(null, "Id");
        String type = reader.getAttributeValue(null, "Type");
        String target = reader.getAttributeValue(null, "Target");
        String mode = reader.getAttributeValue(null, "TargetMode");
        if (id != null && type != null && target != null && !"External".equalsIgnoreCase(mode)) {
          found.add(new Relationship(id, type, resolve(baseDirectory, target)));
        }
      }
    }
    return new Relationships(found);
  }

  /** Resolves a target against its source directory, handling absolute targets and {@code ..}. */
  static String resolve(String baseDirectory, String target) {
    String slashed = target.replace('\\', '/');
    String combined = slashed.startsWith("/") ? slashed : baseDirectory + slashed;
    Deque<String> segments = new ArrayDeque<>();
    for (String segment : combined.split("/", -1)) {
      if (segment.equals("..")) {
        segments.pollLast();
      } else if (!segment.isEmpty() && !segment.equals(".")) {
        segments.addLast(segment);
      }
    }
    return String.join("/", segments);
  }

  Optional<String> targetOfType(String typeSuffix) {
    String suffix = typeSuffix.toLowerCase(Locale.ROOT);
    return all.stream()
        .filter(r -> r.type().toLowerCase(Locale.ROOT).endsWith(suffix))
        .map(Relationship::target)
        .findFirst();
  }

  Optional<Relationship> byId(String id) {
    return all.stream().filter(r -> r.id().equals(id)).findFirst();
  }
}
