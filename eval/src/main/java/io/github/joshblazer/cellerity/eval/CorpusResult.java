package io.github.joshblazer.cellerity.eval;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Totals from a corpus load. See {@code docs/formats/xlsx-subset.md}. */
record CorpusResult(
    int corpusFiles,
    int loaded,
    Map<String, Integer> rejected,
    int crashed,
    long sheets,
    long cells,
    long formulaCells,
    long formulaCellsWithCachedValue,
    long definedNames,
    Map<String, Integer> writers,
    List<Failure> failures,
    ParseStats parse) {

  /** A file that did not load. */
  record Failure(String file, String outcome, String message) {}

  /** The result as JSON, with no dependency on a JSON library. Output order is stable. */
  String toJson(String date) {
    String failureList =
        failures.stream()
            .map(f ->
                "    {\"file\": "
                    + quote(f.file())
                    + ", \"outcome\": "
                    + quote(f.outcome())
                    + ", \"message\": "
                    + quote(f.message())
                    + "}")
            .collect(Collectors.joining(",\n"));
    return "{\n"
        + "  \"schema\": 1,\n"
        + ("  \"date\": " + quote(date) + ",\n")
        + ("  \"corpusFiles\": " + corpusFiles + ",\n")
        + ("  \"loaded\": " + loaded + ",\n")
        + ("  \"rejected\": " + counts(rejected) + ",\n")
        + ("  \"crashed\": " + crashed + ",\n")
        + ("  \"sheets\": " + sheets + ",\n")
        + ("  \"cells\": " + cells + ",\n")
        + ("  \"formulaCells\": " + formulaCells + ",\n")
        + ("  \"formulaCellsWithCachedValue\": " + formulaCellsWithCachedValue + ",\n")
        + ("  \"definedNames\": " + definedNames + ",\n")
        + ("  \"writers\": " + counts(writers) + ",\n")
        + parseJson()
        + "  \"failures\": ["
        + (failures.isEmpty() ? "" : "\n" + failureList + "\n  ")
        + "]\n}\n";
  }

  private String parseJson() {
    String ranked =
        parse.ranked().entrySet().stream()
            .map(e -> "    " + quote(e.getKey()) + ": " + e.getValue())
            .collect(Collectors.joining(",\n"));
    String examples =
        parse.failureExamples().stream().map(CorpusResult::quote).collect(Collectors.joining(", "));
    return "  \"parse\": {\"formulaCells\": "
        + parse.formulaCells()
        + ", \"parsed\": "
        + parse.parsed()
        + ", \"failed\": "
        + parse.failed()
        + ", \"roundTripFailures\": "
        + parse.roundTripFailures()
        + ", \"dataTables\": "
        + parse.dataTables()
        + ", \"rate\": "
        + parse.rate()
        + ", \"failureExamples\": ["
        + examples
        + "]},\n"
        + "  \"functionCalls\": "
        + parse.functionCalls()
        + ",\n  \"functionsRanked\": {\n"
        + ranked
        + "\n  },\n  \"filesUsingFunction\": "
        + counts(parse.filesUsing())
        + ",\n";
  }

  private static String counts(Map<String, ? extends Number> counts) {
    return new TreeMap<>(counts)
        .entrySet().stream()
            .map(e -> quote(e.getKey()) + ": " + e.getValue())
            .collect(Collectors.joining(", ", "{", "}"));
  }

  private static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
