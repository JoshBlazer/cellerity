package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.Dates;
import io.github.joshblazer.cellerity.core.Lift;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.core.functions.Functions;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.Exprs;
import io.github.joshblazer.cellerity.formula.FormulaParser;
import io.github.joshblazer.cellerity.formula.ParseResult;
import io.github.joshblazer.cellerity.formula.ParsedFormula;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.DefinedName;
import io.github.joshblazer.cellerity.io.Sheet;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

/**
 * The simplest correct whole-workbook driver: evaluation on demand, with memoization and cycle
 * detection. Cells it cannot calculate contribute their cached value, and whatever depends on them
 * is marked. See {@code docs/design/evaluator.md}.
 */
public final class WorkbookEvaluator implements Workspace {
  /** Cells deeper than this are evaluated first, so a long chain cannot overflow the stack. */
  private static final int MAX_DEPTH = 150;

  /** How a cell's value was arrived at. */
  public enum Status {
    /** A constant, or blank. */
    STORED,
    CALCULATED,
    /** Cached value used: a parse failure, data table, cycle, or other unsupported feature. */
    UNSUPPORTED_FEATURE,
    /** Cached value used: a call to a function we do not have. */
    UNSUPPORTED_FUNCTION,
    /** Cached value used: a volatile function. */
    VOLATILE
  }

  /**
   * A cell's result.
   *
   * @param detail what was unsupported, or empty
   * @param usedCachedInput whether this result rests on a cached value somewhere upstream
   */
  public record Outcome(Value value, Status status, String detail, boolean usedCachedInput) {}

  /** What to do with volatile functions. */
  public enum VolatilePolicy {
    /** Use the cached value, since a saved {@code NOW()} cannot be reproduced. */
    USE_CACHED,
    /** Calculate them, as a live recalculation would. */
    CALCULATE
  }

  private static final class Deep extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Key cell;

    Deep(Key cell) {
      super(null, null, false, false);
      this.cell = cell;
    }
  }

  private record Key(int sheet, CellRef at) {}

  private final ParsedWorkbook workbook;
  private final FormulaEngine engine;
  private final VolatilePolicy volatilePolicy;
  private final List<Map<CellRef, Cell>> cells = new ArrayList<>();
  private final int[] lastRows;
  private final int[] lastColumns;
  private final Map<Key, Outcome> memo = new HashMap<>();
  private final Map<Key, Value> arrays = new HashMap<>();
  private final Map<Key, Value> overrides = new HashMap<>();
  private final Set<Key> inProgress = new HashSet<>();
  private final Set<Key> cyclic = new HashSet<>();
  private final Map<String, Optional<Expr>> names = new HashMap<>();
  private final Random random = new Random();
  private double nowSerial = Double.NaN;
  private int depth;
  private boolean tainted;
  private long evaluations;

  public WorkbookEvaluator(ParsedWorkbook workbook) {
    this(workbook, new Interpreter(), VolatilePolicy.USE_CACHED);
  }

  public WorkbookEvaluator(
      ParsedWorkbook workbook, FormulaEngine engine, VolatilePolicy volatilePolicy) {
    this.workbook = workbook;
    this.engine = engine;
    this.volatilePolicy = volatilePolicy;
    int count = workbook.workbook().sheets().size();
    lastRows = new int[count];
    lastColumns = new int[count];
    for (int i = 0; i < count; i++) {
      Sheet sheet = workbook.workbook().sheets().get(i);
      cells.add(sheet.cells());
      for (CellRef ref : sheet.cells().keySet()) {
        lastRows[i] = Math.max(lastRows[i], ref.row());
        lastColumns[i] = Math.max(lastColumns[i], ref.column());
      }
    }
  }

  /** Replaces a cell's content with a value, and forgets everything calculated so far. */
  public void set(int sheet, CellRef at, Value value) {
    overrides.put(new Key(sheet, at), value);
    lastRows[sheet] = Math.max(lastRows[sheet], at.row());
    lastColumns[sheet] = Math.max(lastColumns[sheet], at.column());
    memo.clear();
    arrays.clear();
    cyclic.clear();
  }

  /** How many formulas have been evaluated, for tests that count work. */
  public long evaluations() {
    return evaluations;
  }

  /** The calculated value of a cell. */
  public Value value(int sheet, CellRef at) {
    return outcome(sheet, at).value();
  }

  /** A cell's value, and how it was arrived at. */
  public Outcome outcome(int sheet, CellRef at) {
    Key target = new Key(sheet, at);
    Deque<Key> pending = new ArrayDeque<>();
    pending.push(target);
    Set<Key> waiting = new HashSet<>();
    waiting.add(target);
    while (true) {
      Key next = pending.peek();
      depth = 0;
      tainted = false;
      try {
        Outcome outcome = evaluate(next);
        pending.pop();
        waiting.remove(next);
        if (pending.isEmpty()) {
          return outcome;
        }
      } catch (Deep deep) {
        if (waiting.add(deep.cell)) {
          pending.push(deep.cell);
        } else {
          // A cell that is already waiting came round again: only a cycle does that.
          cyclic.add(deep.cell);
          memo.put(deep.cell, cycleOutcome(deep.cell));
        }
      }
    }
  }

  private Outcome cycleOutcome(Key key) {
    Value cached = cached(key).orElse(new Value.Num(0));
    return new Outcome(cached, Status.UNSUPPORTED_FEATURE, "cycle", true);
  }

  private Optional<Value> cached(Key key) {
    Cell cell = cells.get(key.sheet()).get(key.at());
    return cell == null ? Optional.empty() : cell.value();
  }

  @Override
  public Value cell(int sheet, int column, int row) {
    if (sheet < 0 || sheet >= cells.size()) {
      return Value.BLANK;
    }
    if (column < 1 || row < 1 || column > lastColumns[sheet] || row > lastRows[sheet]) {
      return Value.BLANK;
    }
    Key key = new Key(sheet, new CellRef(column, row));
    if (!overrides.isEmpty() && overrides.containsKey(key)) {
      return overrides.get(key);
    }
    Cell cell = cells.get(sheet).get(key.at());
    boolean arrayMember = workbook.arrayMaster(sheet, key.at()).isPresent();
    if (!arrayMember && (cell == null || cell.formula().isEmpty())) {
      return cell == null ? Value.BLANK : cell.value().orElse(Value.BLANK);
    }
    Outcome outcome = evaluate(key);
    tainted |=
        outcome.usedCachedInput()
            || (outcome.status() != Status.CALCULATED && outcome.status() != Status.STORED);
    return outcome.value();
  }

  private Outcome evaluate(Key key) {
    Outcome known = memo.get(key);
    if (known != null) {
      return known;
    }
    if (overrides.containsKey(key)) {
      return new Outcome(overrides.get(key), Status.STORED, "", false);
    }
    Optional<CellRef> master = workbook.arrayMaster(key.sheet(), key.at());
    if (master.isPresent()) {
      return arrayMember(key, new Key(key.sheet(), master.get()));
    }
    ParsedFormula formula = workbook.formulas(key.sheet()).get(key.at());
    if (formula == null) {
      Value stored = cached(key).orElse(Value.BLANK);
      return new Outcome(stored, Status.STORED, "", false);
    }
    if (inProgress.contains(key)) {
      cyclic.add(key);
      tainted = true;
      return cycleOutcome(key);
    }
    if (depth >= MAX_DEPTH) {
      throw new Deep(key);
    }
    boolean outerTaint = tainted;
    tainted = false;
    inProgress.add(key);
    depth++;
    Outcome outcome;
    try {
      outcome = calculate(key, formula);
    } finally {
      depth--;
      inProgress.remove(key);
    }
    if (cyclic.contains(key)) {
      outcome = cycleOutcome(key);
    }
    memo.put(key, outcome);
    tainted = outerTaint;
    return outcome;
  }

  private Outcome calculate(Key key, ParsedFormula formula) {
    Optional<String> unsupported = unsupportedFeature(formula);
    if (unsupported.isPresent()) {
      return substituted(key, Status.UNSUPPORTED_FEATURE, unsupported.get());
    }
    List<String> functions = Exprs.functionNames(formula.expr());
    boolean volatileCall = false;
    for (String function : functions) {
      String canonical = canonical(function);
      if (Functions.VOLATILE.contains(canonical)) {
        volatileCall = true;
      } else if (!Functions.supported(canonical)) {
        return substituted(key, Status.UNSUPPORTED_FUNCTION, canonical);
      }
    }
    if (volatileCall && volatilePolicy == VolatilePolicy.USE_CACHED) {
      return substituted(key, Status.VOLATILE, "volatile");
    }
    boolean array = formula.kind() == ParsedFormula.Kind.ARRAY_MASTER;
    evaluations++;
    try {
      Value result =
          engine.evaluate(
              formula.expr(), key.sheet(), key.at().column(), key.at().row(), array, this);
      if (array) {
        arrays.put(key, result);
        result = Lift.element(result, 0, 0);
      }
      return new Outcome(result, Status.CALCULATED, "", tainted);
    } catch (UnsupportedFeature e) {
      String reason = e.getMessage();
      return reason.startsWith("function ")
          ? substituted(key, Status.UNSUPPORTED_FUNCTION, reason.substring("function ".length()))
          : substituted(key, Status.UNSUPPORTED_FEATURE, reason);
    }
  }

  private static String canonical(String function) {
    return new Expr.Call(function, List.of(), io.github.joshblazer.cellerity.formula.Span.NONE)
        .canonicalName();
  }

  private Outcome substituted(Key key, Status status, String detail) {
    Value cached = cached(key).orElse(Value.BLANK);
    return new Outcome(cached, status, detail, false);
  }

  /** Features a formula uses that are out of scope, without evaluating it. */
  private static Optional<String> unsupportedFeature(ParsedFormula formula) {
    if (formula.kind() == ParsedFormula.Kind.DATA_TABLE) {
      return Optional.of("data table");
    }
    if (!formula.ok()) {
      return Optional.of("parse failure");
    }
    Expr expr = formula.expr();
    if (Exprs.any(expr, e -> e instanceof Expr.Structured)) {
      return Optional.of("structured reference");
    }
    if (Exprs.any(expr, e -> scope(e).map(Expr.Scope::external).orElse(false))) {
      return Optional.of("external reference");
    }
    if (Exprs.any(expr, e -> scope(e).map(Expr.Scope::threeD).orElse(false))) {
      return Optional.of("3D reference");
    }
    if (Exprs.any(expr, e -> e instanceof Expr.Binary b && b.op() == Expr.BinaryOp.UNION)) {
      return Optional.of("union reference");
    }
    return Optional.empty();
  }

  private static Optional<Expr.Scope> scope(Expr expr) {
    return switch (expr) {
      case Expr.Cell c -> Optional.of(c.scope());
      case Expr.Area a -> Optional.of(a.scope());
      case Expr.BrokenRef b -> Optional.of(b.scope());
      case Expr.Name n -> Optional.of(n.scope());
      default -> Optional.empty();
    };
  }

  private Outcome arrayMember(Key key, Key master) {
    Outcome masterOutcome = evaluate(master);
    Value grid = arrays.get(master);
    if (grid == null) {
      // The master was not calculated, so its members keep their cached values too.
      Value cached = cached(key).orElse(Value.BLANK);
      return new Outcome(
          cached, masterOutcome.status(), masterOutcome.detail(), masterOutcome.usedCachedInput());
    }
    int row = key.at().row() - master.at().row();
    int column = key.at().column() - master.at().column();
    return new Outcome(
        Lift.element(grid, row, column), Status.CALCULATED, "", masterOutcome.usedCachedInput());
  }

  @Override
  public int lastRow(int sheet) {
    return lastRows[sheet];
  }

  @Override
  public int lastColumn(int sheet) {
    return lastColumns[sheet];
  }

  @Override
  public DateSystem dates() {
    return workbook.workbook().dateSystem();
  }

  @Override
  public OptionalInt sheetIndex(String name) {
    List<Sheet> sheets = workbook.workbook().sheets();
    for (int i = 0; i < sheets.size(); i++) {
      if (sheets.get(i).name().equalsIgnoreCase(name)) {
        return OptionalInt.of(i);
      }
    }
    return OptionalInt.empty();
  }

  @Override
  public Optional<Expr> name(String name, int fromSheet) {
    String key = fromSheet + "!" + name.toLowerCase(Locale.ROOT);
    return names.computeIfAbsent(
        key,
        k -> {
          Optional<DefinedName> found =
              workbook.workbook().definedName(name, OptionalInt.of(fromSheet));
          return found.map(n -> {
            ParseResult parsed = FormulaParser.parse(n.refersTo());
            return parsed.ok()
                ? parsed.expr()
                : new Expr.Invalid(n.refersTo(), parsed.expr().span());
          });
        });
  }

  @Override
  public double now() {
    if (Double.isNaN(nowSerial)) {
      LocalDateTime time = LocalDateTime.now(java.time.ZoneId.systemDefault());
      nowSerial =
          Dates.serial(time.toLocalDate(), dates()) + time.toLocalTime().toSecondOfDay() / 86_400.0;
    }
    return nowSerial;
  }

  @Override
  public double random() {
    return random.nextDouble();
  }
}
