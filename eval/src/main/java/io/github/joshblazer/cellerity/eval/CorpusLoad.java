package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import io.github.joshblazer.cellerity.io.XlsxReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Loads every workbook under the given directories and reports what happened. A typed rejection is
 * an acceptable outcome for a bad file. A crash, meaning any other throwable or a timeout, is a
 * bug.
 *
 * <p>Usage: {@code CorpusLoad --corpus DIR [--corpus DIR ...] --out FILE}. Exit 0 if nothing
 * crashed, 1 if something did, and 2 on bad usage.
 */
public final class CorpusLoad {
  private static final Duration PER_FILE_TIMEOUT = Duration.ofMinutes(2);

  private CorpusLoad() {}

  public static void main(String[] args) throws IOException {
    List<Path> roots = new ArrayList<>();
    Path out = null;
    Path baseline = null;
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--corpus" -> roots.add(Path.of(args[i + 1]));
        case "--out" -> out = Path.of(args[i + 1]);
        case "--baseline" -> baseline = Path.of(args[i + 1]);
        default -> {
          System.err.println("Unknown argument: " + args[i]);
          System.exit(2);
        }
      }
    }
    if (roots.isEmpty() || out == null || args.length % 2 != 0) {
      System.err.println(
          "usage: CorpusLoad --corpus DIR [--corpus DIR ...] --out FILE [--baseline FILE]");
      System.exit(2);
    }
    CorpusResult result = run(roots, PER_FILE_TIMEOUT);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    Files.writeString(out, result.toJson(LocalDate.now(ZoneOffset.UTC).toString()));
    System.out.printf(
        Locale.ROOT,
        "corpus load: %d files: %d loaded, %d rejected %s, %d crashed%n"
            + "contents: %d sheets, %d cells, %d formula cells (%d with cached values), %d names%n"
            + "writers: %s%n",
        result.corpusFiles(),
        result.loaded(),
        result.rejected().values().stream().mapToInt(Integer::intValue).sum(),
        result.rejected(),
        result.crashed(),
        result.sheets(),
        result.cells(),
        result.formulaCells(),
        result.formulaCellsWithCachedValue(),
        result.definedNames(),
        result.writers());
    result.failures().stream()
        .filter(f -> f.outcome().equals("CRASH"))
        .forEach(f ->
            // CI logs are public, and corpus results are aggregates only (ADR-0013).
            System.out.println(
                System.getenv("CI") == null
                    ? "CRASH " + f.file() + ": " + f.message()
                    : "CRASH (file withheld from public logs)"));
    System.out.printf(
        Locale.ROOT,
        "parse: %d of %d formula cells (%.4f%%), %d failed, %d do not round-trip, %d data tables%n",
        result.parse().parsed(),
        result.parse().formulaCells(),
        100 * result.parse().rate(),
        result.parse().failed(),
        result.parse().roundTripFailures(),
        result.parse().dataTables());
    boolean belowBaseline = baseline != null && !Baseline.load(baseline).check(result);
    // Exit explicitly: a timed-out read may still occupy a worker thread.
    System.exit(result.crashed() == 0 && !belowBaseline ? 0 : 1);
  }

  /** Loads all {@code .xlsx} and {@code .xlsm} files under the roots. */
  static CorpusResult run(List<Path> roots, Duration perFileTimeout) throws IOException {
    Tally tally = new Tally();
    ExecutorService executor = daemonExecutor();
    try {
      for (Path root : roots) {
        for (Path file : workbooksUnder(root)) {
          String label = root.relativize(file).toString().replace('\\', '/');
          Future<Workbook> load = executor.submit(() -> readAny(file));
          try {
            tally.loaded(load.get(perFileTimeout.toMillis(), TimeUnit.MILLISECONDS));
          } catch (ExecutionException e) {
            if (e.getCause() instanceof XlsxReadException rejected) {
              tally.rejected(label, rejected);
            } else {
              tally.crashed(label, String.valueOf(e.getCause()));
            }
          } catch (TimeoutException e) {
            load.cancel(true);
            executor.shutdownNow();
            executor = daemonExecutor();
            tally.crashed(label, "timed out after " + perFileTimeout);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
          }
        }
      }
    } finally {
      executor.shutdownNow();
    }
    return tally.result();
  }

  static Workbook readAny(Path file) throws XlsxReadException {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".xls") ? HssfWorkbooks.read(file) : XlsxReader.read(file);
  }

  private static ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(null, task, "corpus-load");
      thread.setDaemon(true);
      return thread;
    });
  }

  private static List<Path> workbooksUnder(Path root) throws IOException {
    if (!Files.exists(root)) {
      throw new IOException("Corpus directory not found: " + root);
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(CorpusLoad::isWorkbookName)
          .sorted()
          .toList();
    }
  }

  private static boolean isWorkbookName(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls");
  }

  private static final class Tally {
    private int files;
    private int loaded;
    private int crashed;
    private long sheets;
    private long cells;
    private long formulaCells;
    private long formulaCellsWithCachedValue;
    private long definedNames;
    private final Map<String, Integer> rejected = new TreeMap<>();
    private final Map<String, Integer> writers = new TreeMap<>();
    private final List<CorpusResult.Failure> failures = new ArrayList<>();
    private final ParseStats parse = new ParseStats();

    void loaded(Workbook workbook) {
      files++;
      loaded++;
      sheets += workbook.sheets().size();
      definedNames += workbook.definedNames().size();
      writers.merge(workbook.info().application().orElse("(not stated)"), 1, Integer::sum);
      parse.add(ParsedWorkbook.of(workbook));
      for (Sheet sheet : workbook.sheets()) {
        for (Cell cell : sheet.cells().values()) {
          cells++;
          if (cell.formula().isPresent()) {
            formulaCells++;
            if (cell.value().isPresent()) {
              formulaCellsWithCachedValue++;
            }
          }
        }
      }
    }

    void rejected(String label, XlsxReadException e) {
      files++;
      rejected.merge(e.kind().name(), 1, Integer::sum);
      failures.add(new CorpusResult.Failure(label, e.kind().name(), e.getMessage()));
    }

    void crashed(String label, String message) {
      files++;
      crashed++;
      failures.add(new CorpusResult.Failure(label, "CRASH", message));
    }

    CorpusResult result() {
      return new CorpusResult(
          files,
          loaded,
          rejected,
          crashed,
          sheets,
          cells,
          formulaCells,
          formulaCellsWithCachedValue,
          definedNames,
          writers,
          failures,
          parse);
    }
  }
}
