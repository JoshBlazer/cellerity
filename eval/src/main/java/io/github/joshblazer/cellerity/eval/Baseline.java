package io.github.joshblazer.cellerity.eval;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Floors from {@code eval/baseline.properties}. Results below a floor fail the build, so a metric
 * can only regress on purpose, by editing that file. A floor on files and cells also stops a run
 * that checks too little from passing.
 */
final class Baseline {
  private final Properties floors;

  private Baseline(Properties floors) {
    this.floors = floors;
  }

  static Baseline load(Path file) throws IOException {
    Properties floors = new Properties();
    try (Reader reader = Files.newBufferedReader(file)) {
      floors.load(reader);
    }
    return new Baseline(floors);
  }

  private boolean atLeast(String key, double actual) {
    String floor = floors.getProperty(key);
    if (floor == null) {
      return true;
    }
    boolean ok = actual >= Double.parseDouble(floor);
    if (!ok) {
      System.out.println("BELOW BASELINE: " + key + " is " + actual + ", floor is " + floor);
    }
    return ok;
  }

  private boolean atMost(String key, double actual) {
    String ceiling = floors.getProperty(key);
    if (ceiling == null) {
      return true;
    }
    boolean ok = actual <= Double.parseDouble(ceiling);
    if (!ok) {
      System.out.println("ABOVE BASELINE: " + key + " is " + actual + ", limit is " + ceiling);
    }
    return ok;
  }

  /** Reports every floor missed, and returns true if none was. */
  boolean check(CorpusResult result) {
    boolean ok = atLeast("minLoaded", result.loaded());
    ok &= atLeast("minFormulaCells", (double) result.parse().formulaCells());
    ok &= atLeast("minParseRate", result.parse().rate());
    ok &= atMost("maxRoundTripFailures", (double) result.parse().roundTripFailures());
    ok &= atMost("maxCrashed", result.crashed());
    ok &= atLeast("minDataTables", (double) result.parse().dataTables());
    return ok;
  }
}
