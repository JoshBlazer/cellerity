package io.github.joshblazer.cellerity.eval;

import java.util.List;
import java.util.Map;

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
    List<Failure> failures) {

  /** A file that did not load. */
  record Failure(String file, String outcome, String message) {}

  /** The result as JSON, with no dependency on a JSON library. Output order is stable. */
  String toJson(String date) {
    throw new UnsupportedOperationException("not implemented");
  }
}
