package io.github.joshblazer.cellerity.eval;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.graph.DependencyGraph;
import io.github.joshblazer.cellerity.interp.IncrementalWorkbook;
import io.github.joshblazer.cellerity.interp.Interpreter;
import io.github.joshblazer.cellerity.interp.WorkbookEvaluator;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Proves incremental recalculation on real workbooks: it changes inputs, recalculates, and compares
 * every formula cell with a new evaluator given the same inputs. Output is aggregates only, as it
 * is public on CI (ADR-0013).
 */
public final class RecalcCheck {
  private static final int CHANGES_PER_FILE = 3;
  private static final long PER_FILE_SECONDS = 120;

  private long files;
  private long skippedForCycles;
  private long timedOut;
  private long crashed;
  private long changes;
  private long cellsCompared;
  private long disagreements;
  private long evaluated;
  private long evaluatedByFull;
  private long overBudget;

  private RecalcCheck() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    RecalcCheck check = new RecalcCheck();
    long minChanges = 0;
    Path out = null;
    List<Path> roots = new ArrayList<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--corpus" -> roots.add(Path.of(args[i + 1]));
        case "--min-changes" -> minChanges = Long.parseLong(args[i + 1]);
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
        paths = walk.filter(RecalcCheck::isWorkbook).sorted().toList();
      }
      for (Path file : paths) {
        Future<?> run = executor.submit(() -> check.checkFile(file));
        try {
          run.get(PER_FILE_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          check.crashed++;
        } catch (TimeoutException e) {
          check.timedOut++;
          run.cancel(true);
          executor.shutdownNow();
          executor = daemonExecutor();
        }
      }
    }
    System.out.printf(
        Locale.ROOT,
        "recalc: %d files, %d left out for static cycles, %d timed out, %d crashed%n"
            + "%d changes, %d formula cells compared, %d disagreements%n"
            + "evaluated %d formulas, where full recalculations evaluated %d (%.2f%%), %d over budget%n",
        check.files,
        check.skippedForCycles,
        check.timedOut,
        check.crashed,
        check.changes,
        check.cellsCompared,
        check.disagreements,
        check.evaluated,
        check.evaluatedByFull,
        check.evaluatedByFull == 0 ? 0.0 : 100.0 * check.evaluated / check.evaluatedByFull,
        check.overBudget);
    if (out != null) {
      Files.writeString(
          out,
          String.format(
              Locale.ROOT,
              """
              {
                "schema": 1,
                "date": "%s",
                "files": %d,
                "leftOutForStaticCycles": %d,
                "timedOut": %d,
                "crashed": %d,
                "changes": %d,
                "cellsCompared": %d,
                "disagreements": %d,
                "evaluatedIncrementally": %d,
                "evaluatedByFull": %d,
                "overBudget": %d
              }
              """,
              java.time.LocalDate.now(java.time.ZoneOffset.UTC),
              check.files,
              check.skippedForCycles,
              check.timedOut,
              check.crashed,
              check.changes,
              check.cellsCompared,
              check.disagreements,
              check.evaluated,
              check.evaluatedByFull,
              check.overBudget));
    }
    boolean ok =
        check.disagreements == 0
            && check.overBudget == 0
            && check.crashed == 0
            && check.timedOut == 0
            && check.changes >= minChanges;
    System.exit(ok ? 0 : 1);
  }

  private void checkFile(Path file) {
    Workbook workbook;
    try {
      workbook = CorpusLoad.readAny(file);
    } catch (XlsxReadException e) {
      return;
    }
    ParsedWorkbook parsed = ParsedWorkbook.of(workbook);
    WorkbookEvaluator names = new WorkbookEvaluator(parsed);
    DependencyGraph graph = DependencyGraph.build(parsed, names::name);
    if (!graph.cycles().isEmpty()) {
      skippedForCycles++;
      return;
    }
    files++;
    List<Input> inputs = inputsWithReaders(workbook, graph);
    // The file's name makes the choice repeatable without being recorded anywhere.
    Collections.shuffle(inputs, new Random(file.getFileName().toString().hashCode()));
    IncrementalWorkbook incremental =
        new IncrementalWorkbook(
            parsed, new Interpreter(), WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    incremental.recalculate();
    WorkbookEvaluator full =
        new WorkbookEvaluator(
            parsed, new Interpreter(), WorkbookEvaluator.VolatilePolicy.USE_CACHED);
    for (Input input : inputs.subList(0, Math.min(CHANGES_PER_FILE, inputs.size()))) {
      Value changed = new Value.Num(input.value() + 1);
      long budget =
          graph
              .affectedBy(
                  List.of(
                      new io.github.joshblazer.cellerity.graph.CellKey(input.sheet(), input.at())))
              .size();
      incremental.set(input.sheet(), input.at(), changed);
      IncrementalWorkbook.Recalculation done = incremental.recalculate();
      full.set(input.sheet(), input.at(), changed);
      changes++;
      evaluated += done.evaluated();
      if (done.evaluated() > budget) {
        overBudget++;
      }
      long fullBefore = full.evaluations();
      compareAll(workbook, parsed, incremental, full);
      evaluatedByFull += full.evaluations() - fullBefore;
    }
  }

  private void compareAll(
      Workbook workbook,
      ParsedWorkbook parsed,
      IncrementalWorkbook incremental,
      WorkbookEvaluator full) {
    List<Sheet> sheets = workbook.sheets();
    for (int s = 0; s < sheets.size(); s++) {
      for (CellRef at : parsed.formulas(s).keySet()) {
        cellsCompared++;
        if (!incremental.value(s, at).equals(full.value(s, at))) {
          disagreements++;
        }
      }
      for (CellRef at : parsed.arrayMembers(s).keySet()) {
        cellsCompared++;
        if (!incremental.value(s, at).equals(full.value(s, at))) {
          disagreements++;
        }
      }
    }
  }

  private record Input(int sheet, CellRef at, double value) {}

  private static List<Input> inputsWithReaders(Workbook workbook, DependencyGraph graph) {
    List<Input> inputs = new ArrayList<>();
    List<Sheet> sheets = workbook.sheets();
    for (int s = 0; s < sheets.size(); s++) {
      for (Cell cell : sheets.get(s).cells().values()) {
        if (cell.formula().isEmpty()
            && cell.value().orElse(Value.BLANK) instanceof Value.Num number
            && !graph
                .dependents(new io.github.joshblazer.cellerity.graph.CellKey(s, cell.ref()))
                .isEmpty()) {
          inputs.add(new Input(s, cell.ref(), number.value()));
        }
      }
    }
    return inputs;
  }

  private static boolean isWorkbook(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return Files.isRegularFile(file)
        && (name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls"));
  }

  private static ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "recalc");
      thread.setDaemon(true);
      return thread;
    });
  }
}
