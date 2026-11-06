package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.interp.Category;
import io.github.joshblazer.cellerity.interp.Comparison;
import io.github.joshblazer.cellerity.interp.Coverage;
import io.github.joshblazer.cellerity.interp.Verifier;
import io.github.joshblazer.cellerity.interp.VerifyResult;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs {@code verify} over a corpus. The headline counts only files that say Excel wrote them,
 * since other writers' cached values are lower-trust.
 *
 * <p>Standard output and {@code --out} are public on CI, so they hold aggregates only: no file
 * name, sheet name, cell address, formula text, or value (ADR-0013). Those go only to the {@code
 * --detail} file, which is for local use and must never be committed or uploaded.
 */
public final class Fidelity {
  private static final int MISMATCHES_KEPT = 100_000;
  private static final long PER_FILE_SECONDS = 300;
  private static final int PATTERNS_PRINTED = 60;
  private static final int PATTERNS_SAVED = 300;
  private static final int PROGRESS_EVERY = 50;

  private Fidelity() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    List<Path> roots = new ArrayList<>();
    Path out = null;
    Path detail = null;
    Path baseline = null;
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--corpus" -> roots.add(Path.of(args[i + 1]));
        case "--out" -> out = Path.of(args[i + 1]);
        case "--detail" -> detail = Path.of(args[i + 1]);
        case "--baseline" -> baseline = Path.of(args[i + 1]);
        default -> {
          System.err.println("Unknown argument: " + args[i]);
          System.exit(2);
        }
      }
    }
    if (roots.isEmpty() || out == null) {
      System.err.println(
          "usage: Fidelity --corpus DIR --out FILE [--detail FILE] [--baseline FILE]");
      System.exit(2);
    }
    Map<String, VerifyResult> byWriter = new TreeMap<>();
    Coverage coverage = new Coverage();
    int files = 0;
    int skipped = 0;
    int timedOut = 0;
    Map<String, Long> crashes = new TreeMap<>();
    List<Long> perFile = new ArrayList<>();
    int seen = 0;
    ExecutorService executor = daemonExecutor();
    PrintWriter details =
        detail == null
            ? null
            : new PrintWriter(Files.newBufferedWriter(detail, StandardCharsets.UTF_8));
    for (Path root : roots) {
      List<Path> paths;
      try (Stream<Path> walk = Files.walk(root)) {
        paths = walk.filter(Fidelity::isWorkbook).sorted().collect(Collectors.toList());
      }
      for (Path file : paths) {
        seen++;
        if (seen % PROGRESS_EVERY == 0) {
          System.out.println("progress: " + seen + " files");
        }
        Future<Run> run = executor.submit(() -> runOne(file));
        try {
          Run done = run.get(PER_FILE_SECONDS, TimeUnit.SECONDS);
          if (done == null) {
            skipped++;
            continue;
          }
          files++;
          byWriter
              .computeIfAbsent(
                  label(done.workbook().workbook()), k -> new VerifyResult(MISMATCHES_KEPT))
              .merge(done.result());
          coverage.add(done.workbook());
          long wrong = done.result().count(Category.REAL_MISMATCH);
          if (wrong > 0) {
            perFile.add(wrong);
          }
          if (details != null) {
            for (VerifyResult.Mismatch m : done.result().mismatches()) {
              details.println(
                  String.join(
                      "\t",
                      file.getFileName().toString(),
                      m.sheet(),
                      m.cell(),
                      m.function(),
                      m.expected(),
                      m.actual(),
                      m.root() ? "root" : "downstream",
                      m.pattern(),
                      m.formula()));
            }
          }
        } catch (ExecutionException e) {
          crashes.merge(e.getCause().getClass().getSimpleName(), 1L, Long::sum);
          if (details != null) {
            details.println("CRASH\t" + file.getFileName() + "\t" + e.getCause());
          }
        } catch (TimeoutException e) {
          timedOut++;
          if (details != null) {
            details.println("TIMEOUT\t" + file.getFileName());
          }
          run.cancel(true);
          executor.shutdownNow();
          executor = daemonExecutor();
        }
      }
    }
    if (details != null) {
      details.close();
    }
    VerifyResult excel = new VerifyResult(0);
    VerifyResult all = new VerifyResult(0);
    byWriter.forEach((writer, result) -> {
      all.merge(result);
      if (writer.equals("Excel")) {
        excel.merge(result);
      }
    });
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    Files.writeString(
        out, json(files, skipped, timedOut, crashes, perFile, excel, byWriter, coverage));
    print("Excel-labeled (headline)", excel);
    byWriter.forEach((writer, result) -> {
      if (!writer.equals("Excel")) {
        print(writer + " (lower-trust)", result);
      }
    });
    System.out.printf(
        Locale.ROOT,
        "files: %d verified, %d not readable, %d timed out, %d crashed%n"
            + "crash kinds: %s%n"
            + "files with a real mismatch: %d, largest: %s%n"
            + "function coverage: %.2f%% of %d calls%n"
            + "missing functions: %s%n",
        files,
        skipped,
        timedOut,
        crashes.values().stream().mapToLong(Long::longValue).sum(),
        crashes,
        perFile.size(),
        largest(perFile),
        100 * coverage.rate(),
        coverage.totalCalls(),
        top(coverage.missing(), 12));
    System.out.println(
        "mismatches by function: " + top(sortedByValue(excel.mismatchFunctions()), 25));
    System.out.println("root mismatches (no other wrong cell read): " + excel.rootMismatches());
    printPatterns("root patterns", excel.rootPatterns());
    printPatterns("all patterns", excel.mismatchPatterns());
    boolean ok = crashes.isEmpty() && timedOut == 0;
    if (baseline != null) {
      ok &= checkBaseline(baseline, excel, coverage);
    }
    System.exit(ok ? 0 : 1);
  }

  private record Run(ParsedWorkbook workbook, VerifyResult result) {}

  private static Run runOne(Path file) throws XlsxReadException {
    Workbook workbook;
    try {
      workbook = CorpusLoad.readAny(file);
    } catch (XlsxReadException e) {
      return null;
    }
    ParsedWorkbook parsed = ParsedWorkbook.of(workbook);
    return new Run(parsed, Verifier.verify(parsed, MISMATCHES_KEPT));
  }

  private static boolean isWorkbook(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return Files.isRegularFile(file)
        && (name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls"));
  }

  /** "Excel", or the lower-trust group. The label is a claim by the file, not proof. */
  private static String label(Workbook workbook) {
    String writer = workbook.info().application().orElse("(not stated)");
    return writer.toLowerCase(Locale.ROOT).contains("excel") ? "Excel" : "Other or unstated";
  }

  private static ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "fidelity");
      thread.setDaemon(true);
      return thread;
    });
  }

  private static void print(String label, VerifyResult r) {
    System.out.printf(
        Locale.ROOT,
        "%s: fidelity %.4f%% of %d compared; coverage %.2f%% of %d formula cells%n"
            + "  not comparable because: %s%n"
            + "  exact %d, display-only %d, within tolerance %d, REAL MISMATCH %d%n"
            + "  unsupported function %d, unsupported feature %d, not comparable %d%n"
            + "  compared cells resting on a cached input: %d%n"
            + "  features: %s%n",
        label,
        100 * r.fidelity(),
        r.compared(),
        100 * r.coverage(),
        r.total(),
        r.notComparable(),
        r.count(Category.EXACT),
        r.count(Category.DISPLAY_ONLY),
        r.count(Category.WITHIN_TOLERANCE),
        r.count(Category.REAL_MISMATCH),
        r.count(Category.UNSUPPORTED_FUNCTION),
        r.count(Category.UNSUPPORTED_FEATURE),
        r.count(Category.NOT_COMPARABLE),
        r.comparedWithCachedInput(),
        r.unsupportedFeatures());
  }

  private static void printPatterns(String title, Map<String, Long> patterns) {
    System.out.println(title + ":");
    sortedByValue(patterns).entrySet().stream()
        .limit(PATTERNS_PRINTED)
        .forEach(e -> System.out.println("  " + e.getValue() + "\t" + e.getKey()));
  }

  private static List<Long> largest(List<Long> perFile) {
    return perFile.stream().sorted(java.util.Comparator.reverseOrder()).limit(10).toList();
  }

  private static Map<String, Long> sortedByValue(Map<String, Long> map) {
    Map<String, Long> out = new java.util.LinkedHashMap<>();
    map.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  private static String top(Map<String, Long> map, int limit) {
    return map.entrySet().stream()
        .limit(limit)
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private static boolean checkBaseline(Path file, VerifyResult excel, Coverage coverage)
      throws IOException {
    Properties floors = new Properties();
    try (var reader = Files.newBufferedReader(file)) {
      floors.load(reader);
    }
    boolean ok = atLeast(floors, "minFidelity", excel.fidelity());
    ok &= atLeast(floors, "minCompared", (double) excel.compared());
    ok &= atLeast(floors, "minCoverage", excel.coverage());
    ok &= atLeast(floors, "minFunctionCoverage", coverage.rate());
    return ok;
  }

  private static boolean atLeast(Properties floors, String key, double actual) {
    String floor = floors.getProperty(key);
    boolean ok = floor == null || actual >= Double.parseDouble(floor);
    if (!ok) {
      System.out.println("BELOW BASELINE: " + key + " is " + actual + ", floor is " + floor);
    }
    return ok;
  }

  private static String json(
      int files,
      int skipped,
      int timedOut,
      Map<String, Long> crashes,
      List<Long> perFile,
      VerifyResult excel,
      Map<String, VerifyResult> byWriter,
      Coverage coverage) {
    StringBuilder out = new StringBuilder("{\n  \"schema\": 1,\n");
    out.append("  \"date\": \"").append(LocalDate.now(ZoneOffset.UTC)).append("\",\n");
    out.append("  \"tolerance\": ").append(Comparison.TOLERANCE).append(",\n");
    out.append("  \"files\": ").append(files).append(",\n");
    out.append("  \"notReadable\": ").append(skipped).append(",\n");
    out.append("  \"timedOut\": ").append(timedOut).append(",\n");
    out.append("  \"crashes\": ").append(counts(crashes)).append(",\n");
    out.append("  \"filesWithRealMismatch\": ").append(perFile.size()).append(",\n");
    out.append("  \"largestFileMismatches\": ").append(largest(perFile)).append(",\n");
    out.append("  \"functionCoverage\": ").append(coverage.rate()).append(",\n");
    out.append("  \"functionCalls\": ").append(coverage.totalCalls()).append(",\n");
    out.append("  \"missingFunctions\": ").append(counts(coverage.missing())).append(",\n");
    out.append("  \"headlineExcelLabeled\": ").append(summary(excel)).append(",\n");
    out.append("  \"byWriter\": {");
    out.append(
        byWriter.entrySet().stream()
            .map(e -> "\n    " + quote(e.getKey()) + ": " + summary(e.getValue()))
            .collect(Collectors.joining(",")));
    return out.append("\n  }\n}\n").toString();
  }

  private static String summary(VerifyResult r) {
    return String.format(
        Locale.ROOT,
        "{\"fidelity\": %s, \"coverage\": %s, \"formulaCells\": %d, \"compared\": %d, \"exact\": %d,"
            + " \"displayOnly\": %d, \"withinTolerance\": %d, \"realMismatch\": %d,"
            + " \"unsupportedFunction\": %d, \"unsupportedFeature\": %d, \"notComparable\": %d,"
            + " \"notComparableReasons\": %s,"
            + " \"comparedWithCachedInput\": %d, \"rootMismatches\": %d,"
            + " \"mismatchesByFunction\": %s, \"rootPatterns\": %s, \"allPatterns\": %s,"
            + " \"unsupportedFeatures\": %s}",
        r.fidelity(),
        r.coverage(),
        r.total(),
        r.compared(),
        r.count(Category.EXACT),
        r.count(Category.DISPLAY_ONLY),
        r.count(Category.WITHIN_TOLERANCE),
        r.count(Category.REAL_MISMATCH),
        r.count(Category.UNSUPPORTED_FUNCTION),
        r.count(Category.UNSUPPORTED_FEATURE),
        r.count(Category.NOT_COMPARABLE),
        counts(r.notComparable()),
        r.comparedWithCachedInput(),
        r.rootMismatches(),
        counts(r.mismatchFunctions()),
        counts(mostCommon(r.rootPatterns())),
        counts(mostCommon(r.mismatchPatterns())),
        counts(r.unsupportedFeatures()));
  }

  private static Map<String, Long> mostCommon(Map<String, Long> patterns) {
    Map<String, Long> kept = new java.util.LinkedHashMap<>();
    sortedByValue(patterns).entrySet().stream()
        .limit(PATTERNS_SAVED)
        .forEach(e -> kept.put(e.getKey(), e.getValue()));
    return kept;
  }

  private static String counts(Map<String, Long> map) {
    return map.entrySet().stream()
        .map(e -> quote(e.getKey()) + ": " + e.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
