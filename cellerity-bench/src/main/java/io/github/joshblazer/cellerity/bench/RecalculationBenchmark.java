package io.github.joshblazer.cellerity.bench;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.interp.FormulaEngine;
import io.github.joshblazer.cellerity.interp.IncrementalWorkbook;
import io.github.joshblazer.cellerity.interp.Interpreter;
import io.github.joshblazer.cellerity.interp.WorkbookEvaluator;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import io.github.joshblazer.cellerity.io.XlsxReader;
import io.github.joshblazer.cellerity.truffle.TruffleEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Recalculation of one synthetic model. Methodology is in {@code docs/benchmarks/README.md}.
 *
 * <p>Setup checks that each engine agrees with POI on the model, so that no engine can be fast by
 * being wrong. POI's evaluator appears here only as the thing compared with (ADR-0018).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class RecalculationBenchmark {
  /** One way of keeping the model up to date. */
  private interface Runner extends AutoCloseable {
    /** Changes an input and recalculates everything from scratch. Returns a checksum. */
    double full(int input, double value);

    /** Changes an input and brings every cell up to date as cheaply as it can. */
    double changeOne(int input, double value);

    @Override
    void close() throws IOException;
  }

  @Param({"interpreter", "truffle", "poi"})
  public String engine;

  @Param({"2000"})
  public int rows;

  private Path file;
  private Runner runner;
  private int tick;

  @Setup(Level.Trial)
  public void setUp() throws IOException, XlsxReadException {
    file = ModelBook.write(rows);
    runner =
        switch (engine) {
          case "interpreter" -> new Ours(file, new Interpreter());
          case "truffle" -> new Ours(file, new TruffleEngine());
          case "poi" -> new Poi(file);
          default -> throw new IllegalArgumentException(engine);
        };
    try (Runner reference = new Poi(file)) {
      double expected = reference.full(0, 123.0);
      double actual = runner.full(0, 123.0);
      if (Math.abs(expected - actual) > 1e-6 * Math.abs(expected)) {
        throw new IllegalStateException(engine + " gave " + actual + ", POI gave " + expected);
      }
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    runner.close();
    Files.deleteIfExists(file);
  }

  private int nextInput() {
    tick++;
    return tick % ModelBook.INPUTS;
  }

  private double nextValue() {
    return 100.0 + tick % 97;
  }

  @Benchmark
  public double fullRecalculation() {
    return runner.full(nextInput(), nextValue());
  }

  @Benchmark
  public double oneInputChanged() {
    return runner.changeOne(nextInput(), nextValue());
  }

  /** Cellerity, with either engine. */
  private static final class Ours implements Runner {
    private final WorkbookEvaluator evaluator;
    private final IncrementalWorkbook incremental;
    private final List<CellRef> formulas;
    private final CellRef total;

    Ours(Path file, FormulaEngine engine) throws XlsxReadException {
      ParsedWorkbook book = ParsedWorkbook.of(XlsxReader.read(file));
      evaluator = new WorkbookEvaluator(book, engine, WorkbookEvaluator.VolatilePolicy.USE_CACHED);
      // Its own engine: the two runners must not warm each other up differently from POI's.
      FormulaEngine second =
          engine instanceof TruffleEngine ? new TruffleEngine() : new Interpreter();
      incremental =
          new IncrementalWorkbook(book, second, WorkbookEvaluator.VolatilePolicy.USE_CACHED);
      incremental.recalculate();
      formulas = new ArrayList<>(book.formulas(1).keySet());
      total = formulas.get(formulas.size() - 3);
    }

    @Override
    public double full(int input, double value) {
      evaluator.set(0, new CellRef(1, input + 1), new Value.Num(value));
      double sum = 0;
      for (CellRef cell : formulas) {
        if (evaluator.value(1, cell) instanceof Value.Num number) {
          sum += number.value();
        }
      }
      return sum;
    }

    @Override
    public double changeOne(int input, double value) {
      incremental.set(0, new CellRef(1, input + 1), new Value.Num(value));
      incremental.recalculate();
      return ((Value.Num) incremental.value(1, total)).value();
    }

    @Override
    public void close() {}
  }

  /** Apache POI's own evaluator. */
  private static final class Poi implements Runner {
    private final XSSFWorkbook book;
    private final XSSFFormulaEvaluator evaluator;
    private final List<Cell> formulas = new ArrayList<>();
    private final Cell total;

    Poi(Path file) throws IOException {
      try (var in = Files.newInputStream(file)) {
        book = new XSSFWorkbook(in);
      }
      evaluator = book.getCreationHelper().createFormulaEvaluator();
      for (Row row : book.getSheet("Calc")) {
        for (Cell cell : row) {
          if (cell.getCellType() == CellType.FORMULA) {
            formulas.add(cell);
          }
        }
      }
      total = formulas.get(formulas.size() - 3);
    }

    @Override
    public double full(int input, double value) {
      book.getSheet("Inputs").getRow(input).getCell(0).setCellValue(value);
      evaluator.clearAllCachedResultValues();
      return evaluateAll();
    }

    @Override
    public double changeOne(int input, double value) {
      Cell cell = book.getSheet("Inputs").getRow(input).getCell(0);
      cell.setCellValue(value);
      evaluator.notifyUpdateCell(cell);
      // POI recalculates lazily, so asking for every formula is what brings them all up to date.
      evaluateAll();
      return evaluator.evaluate(total).getNumberValue();
    }

    private double evaluateAll() {
      double sum = 0;
      for (Cell cell : formulas) {
        sum += evaluator.evaluate(cell).getNumberValue();
      }
      return sum;
    }

    @Override
    public void close() throws IOException {
      book.close();
    }
  }
}
