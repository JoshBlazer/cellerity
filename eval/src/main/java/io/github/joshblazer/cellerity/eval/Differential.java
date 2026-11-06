package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.interp.WorkbookEvaluator;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import io.github.joshblazer.cellerity.truffle.TruffleEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Compares the Truffle engine with the interpreter on every formula cell of every corpus workbook,
 * and fails on any disagreement. Output is aggregates only, as it is public on CI (ADR-0013).
 */
public final class Differential {
  private static final long PER_FILE_SECONDS = 300;

  private long files;
  private long timedOut;
  private long crashed;
  private long cells;
  private long disagreements;
  private long nodes;
  private long delegated;
  private final TreeMap<String, Long> disagreementKinds = new TreeMap<>();

  private Differential() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    Differential check = new Differential();
    long minCells = 0;
    Path out = null;
    List<Path> roots = new ArrayList<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--corpus" -> roots.add(Path.of(args[i + 1]));
        case "--min-cells" -> minCells = Long.parseLong(args[i + 1]);
        case "--out" -> out = Path.of(args[i + 1]);
        default -> {
          System.err.println("Unknown argument: " + args[i]);
          System.exit(2);
        }
      }
    }
    ExecutorService executor = daemonExecutor();
    for (Path root : roots) {
      List<Path> paths;
      try (Stream<Path> walk = Files.walk(root)) {
        paths = walk.filter(Differential::isWorkbook).sorted().toList();
      }
      for (Path file : paths) {
        Future<?> run = executor.submit(() -> check.compare(file));
        try {
          run.get(PER_FILE_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
          check.crashed++;
          check.disagreementKinds.merge(
              "crash " + e.getCause().getClass().getSimpleName(), 1L, Long::sum);
        } catch (TimeoutException e) {
          check.timedOut++;
          run.cancel(true);
          executor.shutdownNow();
          executor = daemonExecutor();
        }
      }
    }
    String runtime = TruffleEngine.runtimeName();
    double delegatedShare = check.nodes == 0 ? 0 : 100.0 * check.delegated / check.nodes;
    System.out.printf(
        Locale.ROOT,
        "differential on %s, %s %s%n"
            + "%d files, %d timed out, %d crashed%n"
            + "%d formula cells compared, %d disagreements %s%n"
            + "%d nodes built, %d delegated to the interpreter (%.2f%%)%n",
        runtime,
        System.getProperty("java.vm.name"),
        System.getProperty("java.version"),
        check.files,
        check.timedOut,
        check.crashed,
        check.cells,
        check.disagreements,
        check.disagreementKinds,
        check.nodes,
        check.delegated,
        delegatedShare);
    if (out != null) {
      Files.writeString(
          out,
          String.format(
              Locale.ROOT,
              """
              {
                "schema": 1,
                "date": "%s",
                "truffleRuntime": "%s",
                "javaVm": "%s %s",
                "files": %d,
                "timedOut": %d,
                "crashed": %d,
                "cellsCompared": %d,
                "disagreements": %d,
                "nodes": %d,
                "delegatedNodes": %d
              }
              """,
              java.time.LocalDate.now(java.time.ZoneOffset.UTC),
              runtime,
              System.getProperty("java.vm.name"),
              System.getProperty("java.version"),
              check.files,
              check.timedOut,
              check.crashed,
              check.cells,
              check.disagreements,
              check.nodes,
              check.delegated));
    }
    boolean ok =
        check.disagreements == 0
            && check.crashed == 0
            && check.timedOut == 0
            && check.cells >= minCells;
    System.exit(ok ? 0 : 1);
  }

  private void compare(Path file) {
    Workbook workbook;
    try {
      workbook = CorpusLoad.readAny(file);
    } catch (XlsxReadException e) {
      return;
    }
    files++;
    ParsedWorkbook parsed = ParsedWorkbook.of(workbook);
    WorkbookEvaluator interpreter = new WorkbookEvaluator(parsed);
    TruffleEngine engine = new TruffleEngine();
    WorkbookEvaluator truffle =
        new WorkbookEvaluator(parsed, engine, WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    List<Sheet> sheets = workbook.sheets();
    for (int s = 0; s < sheets.size(); s++) {
      compareCells(parsed.formulas(s).keySet(), s, interpreter, truffle);
      compareCells(parsed.arrayMembers(s).keySet(), s, interpreter, truffle);
    }
    nodes += engine.nodes();
    delegated += engine.delegatedNodes();
  }

  private void compareCells(
      Set<CellRef> at, int sheet, WorkbookEvaluator interpreter, WorkbookEvaluator truffle) {
    for (CellRef cell : at) {
      cells++;
      WorkbookEvaluator.Outcome expected = interpreter.outcome(sheet, cell);
      WorkbookEvaluator.Outcome actual = truffle.outcome(sheet, cell);
      if (!expected.equals(actual)) {
        disagreements++;
        // Kinds of value and status only. The values themselves stay out of public logs.
        disagreementKinds.merge(
            expected.value().getClass().getSimpleName()
                + "/"
                + expected.status()
                + " vs "
                + actual.value().getClass().getSimpleName()
                + "/"
                + actual.status(),
            1L,
            Long::sum);
      }
    }
  }

  private static boolean isWorkbook(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return Files.isRegularFile(file)
        && (name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls"));
  }

  private static ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "differential");
      thread.setDaemon(true);
      return thread;
    });
  }
}
