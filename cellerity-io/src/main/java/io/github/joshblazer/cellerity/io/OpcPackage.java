package io.github.joshblazer.cellerity.io;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** The zip package: part lookup, size limits, and safe XML parsing. */
final class OpcPackage implements Closeable {
  /** Parses one XML part. */
  @FunctionalInterface
  interface PartParser<T> {
    T parse(XMLStreamReader reader) throws XMLStreamException, XlsxReadException;
  }

  private final ZipFile zip;
  private final Map<String, ZipEntry> entries = new HashMap<>();
  private final ReadLimits limits;
  private final XMLInputFactory xml;
  private long totalBytes;
  private String limitFailure;

  private OpcPackage(ZipFile zip, ReadLimits limits) {
    this.zip = zip;
    this.limits = limits;
    this.xml = secureFactory();
    var all = zip.entries();
    while (all.hasMoreElements()) {
      ZipEntry entry = all.nextElement();
      entries.putIfAbsent(normalize(entry.getName()), entry);
    }
  }

  static OpcPackage open(Path path, ReadLimits limits) throws XlsxReadException {
    try {
      return new OpcPackage(new ZipFile(path.toFile()), limits);
    } catch (ZipException | IllegalArgumentException e) {
      throw new XlsxReadException(
          XlsxReadException.Kind.MALFORMED, "Corrupt zip package: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new XlsxReadException(
          XlsxReadException.Kind.IO, "Could not open the file: " + e.getMessage(), e);
    }
  }

  private static XMLInputFactory secureFactory() {
    // newDefaultFactory: nothing on the class path can substitute a less safe parser.
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    return factory;
  }

  private static String normalize(String partName) {
    String name = partName.replace('\\', '/');
    name = name.startsWith("/") ? name.substring(1) : name;
    return name.toLowerCase(Locale.ROOT);
  }

  boolean contains(String part) {
    return entries.containsKey(normalize(part));
  }

  /**
   * The relationships of a part, or of the package itself when {@code part} is empty. A missing
   * relationships part means there are none.
   */
  Relationships relationships(String part) throws XlsxReadException {
    String slashed = part.replace('\\', '/');
    int slash = slashed.lastIndexOf('/');
    String directory = slash >= 0 ? slashed.substring(0, slash + 1) : "";
    String file = slashed.substring(slash + 1);
    String relsPart = directory + "_rels/" + file + ".rels";
    if (!contains(relsPart)) {
      return Relationships.EMPTY;
    }
    return parse(relsPart, reader -> Relationships.parse(reader, directory));
  }

  /** Parses a part that must exist. All parser and size failures become typed exceptions. */
  <T> T parse(String part, PartParser<T> parser) throws XlsxReadException {
    ZipEntry entry = entries.get(normalize(part));
    if (entry == null) {
      throw new XlsxReadException(XlsxReadException.Kind.MALFORMED, "Part is missing: " + part);
    }
    try (InputStream in = new LimitedStream(zip.getInputStream(entry), part)) {
      XMLStreamReader reader = xml.createXMLStreamReader(in);
      try {
        return parser.parse(reader);
      } finally {
        reader.close();
      }
    } catch (XlsxReader.LimitExceededXmlException e) {
      throw new XlsxReadException(XlsxReadException.Kind.LIMIT_EXCEEDED, e.getMessage(), e);
    } catch (XMLStreamException | IOException e) {
      // The parser wraps stream failures, so the limit is recorded separately rather than detected
      // from the exception type.
      if (limitFailure != null) {
        throw new XlsxReadException(XlsxReadException.Kind.LIMIT_EXCEEDED, limitFailure, e);
      }
      throw new XlsxReadException(
          XlsxReadException.Kind.MALFORMED, "Invalid part " + part + ": " + e.getMessage(), e);
    }
  }

  @Override
  public void close() throws IOException {
    zip.close();
  }

  /** Counts actual decompressed bytes, because sizes declared in a zip can lie. */
  private final class LimitedStream extends FilterInputStream {
    private final String part;
    private long partBytes;

    LimitedStream(InputStream in, String part) {
      super(in);
      this.part = part;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = super.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws IOException {
      partBytes += n;
      totalBytes += n;
      if (partBytes > limits.maxPartBytes()) {
        fail("Part " + part + " exceeds " + limits.maxPartBytes() + " bytes");
      }
      if (totalBytes > limits.maxTotalBytes()) {
        fail("Workbook exceeds " + limits.maxTotalBytes() + " decompressed bytes");
      }
    }

    private void fail(String message) throws IOException {
      limitFailure = message;
      throw new IOException(message);
    }
  }
}
