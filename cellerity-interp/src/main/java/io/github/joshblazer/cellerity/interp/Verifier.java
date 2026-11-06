package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionError;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.Exprs;
import io.github.joshblazer.cellerity.formula.FormulaPrinter;
import io.github.joshblazer.cellerity.formula.ParsedFormula;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Sheet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Checks every formula cell of a workbook against Excel's cached value. */
public final class Verifier {
  private static final String ADD_IN = "add-in not loaded when saved";
  private static final String ADD_IN_DOWNSTREAM = "reads a cell whose add-in was not loaded";

  /**
   * Functions that needed the Analysis ToolPak add-in before Excel 2007. A file saved without it
   * stores {@code #NAME?}, which says nothing about what the function returns (ADR-0014).
   */
  private static final Set<String> ANALYSIS_TOOLPAK =
      Set.of(
          "ACCRINT",
          "ACCRINTM",
          "AMORDEGRC",
          "AMORLINC",
          "BESSELI",
          "BESSELJ",
          "BESSELK",
          "BESSELY",
          "BIN2DEC",
          "BIN2HEX",
          "BIN2OCT",
          "COMPLEX",
          "CONVERT",
          "COUPDAYBS",
          "COUPDAYS",
          "COUPDAYSNC",
          "COUPNCD",
          "COUPNUM",
          "COUPPCD",
          "CUMIPMT",
          "CUMPRINC",
          "DEC2BIN",
          "DEC2HEX",
          "DEC2OCT",
          "DELTA",
          "DISC",
          "DOLLARDE",
          "DOLLARFR",
          "DURATION",
          "EDATE",
          "EFFECT",
          "EOMONTH",
          "ERF",
          "ERFC",
          "FACTDOUBLE",
          "FVSCHEDULE",
          "GCD",
          "GESTEP",
          "HEX2BIN",
          "HEX2DEC",
          "HEX2OCT",
          "IMABS",
          "IMAGINARY",
          "IMARGUMENT",
          "IMCONJUGATE",
          "IMCOS",
          "IMDIV",
          "IMEXP",
          "IMLN",
          "IMLOG10",
          "IMLOG2",
          "IMPOWER",
          "IMPRODUCT",
          "IMREAL",
          "IMSIN",
          "IMSQRT",
          "IMSUB",
          "IMSUM",
          "INTRATE",
          "ISEVEN",
          "ISODD",
          "LCM",
          "MDURATION",
          "MROUND",
          "MULTINOMIAL",
          "NETWORKDAYS",
          "NOMINAL",
          "OCT2BIN",
          "OCT2DEC",
          "OCT2HEX",
          "ODDFPRICE",
          "ODDFYIELD",
          "ODDLPRICE",
          "ODDLYIELD",
          "PRICE",
          "PRICEDISC",
          "PRICEMAT",
          "QUOTIENT",
          "RANDBETWEEN",
          "RECEIVED",
          "SERIESSUM",
          "SQRTPI",
          "TBILLEQ",
          "TBILLPRICE",
          "TBILLYIELD",
          "WEEKNUM",
          "WORKDAY",
          "XIRR",
          "XNPV",
          "YEARFRAC",
          "YIELD",
          "YIELDDISC",
          "YIELDMAT");

  /** The accuracy Microsoft documents for each iterative function (ADR-0015). */
  private static final Map<String, Double> SOLVER_ACCURACY =
      Map.of("XIRR", 1e-8, "IRR", 1e-7, "RATE", 1e-7);

  private Verifier() {}

  /** Runs the check with the reference interpreter. */
  public static VerifyResult verify(ParsedWorkbook workbook, int mismatchLimit) {
    return verify(workbook, new WorkbookEvaluator(workbook), mismatchLimit);
  }

  /** Runs the check with a given evaluator. */
  public static VerifyResult verify(
      ParsedWorkbook workbook, WorkbookEvaluator evaluator, int mismatchLimit) {
    VerifyResult result = new VerifyResult(mismatchLimit);
    List<Sheet> sheets = workbook.workbook().sheets();
    List<Pending> pending = new ArrayList<>();
    Map<Integer, Set<CellRef>> addInCells = new HashMap<>();
    for (int s = 0; s < sheets.size(); s++) {
      Sheet sheet = sheets.get(s);
      for (Cell cell : sheet.cells().values()) {
        Optional<CellRef> master = workbook.arrayMaster(s, cell.ref());
        ParsedFormula formula =
            workbook.formulas(s).get(master.isPresent() ? master.get() : cell.ref());
        if (formula != null) {
          check(
              s,
              sheet.name(),
              cell,
              formula,
              evaluator.outcome(s, cell.ref()),
              result,
              pending,
              addInCells);
        }
      }
    }
    report(withoutAddInErrors(pending, sheets, addInCells, result), sheets, result);
    return result;
  }

  private record Pending(
      int sheetIndex, String sheet, CellRef ref, Expr expr, Value expected, Value actual) {}

  /**
   * Takes out cells that stored {@code #NAME?} only because they read a cell whose add-in was not
   * loaded, repeating so that it follows chains of such cells.
   */
  private static List<Pending> withoutAddInErrors(
      List<Pending> pending,
      List<Sheet> sheets,
      Map<Integer, Set<CellRef>> addInCells,
      VerifyResult result) {
    List<Pending> left = new ArrayList<>(pending);
    boolean changed = !addInCells.isEmpty();
    while (changed) {
      changed = false;
      for (Iterator<Pending> it = left.iterator(); it.hasNext(); ) {
        Pending p = it.next();
        if (isNameError(p.expected()) && readsWrongCell(p, sheets, addInCells)) {
          it.remove();
          result.notComparable(ADD_IN_DOWNSTREAM);
          addInCells.computeIfAbsent(p.sheetIndex(), k -> new HashSet<>()).add(p.ref());
          changed = true;
        }
      }
    }
    return left;
  }

  private static boolean isNameError(Value value) {
    return value instanceof Value.Err e && e.error() == ExcelError.NAME;
  }

  /** Runs after all cells are checked, since a root is a cell reading no other wrong cell. */
  private static void report(List<Pending> pending, List<Sheet> sheets, VerifyResult result) {
    Map<Integer, Set<CellRef>> wrong = new HashMap<>();
    for (Pending p : pending) {
      wrong.computeIfAbsent(p.sheetIndex(), k -> new HashSet<>()).add(p.ref());
    }
    for (Pending p : pending) {
      result.add(Category.REAL_MISMATCH);
      result.mismatch(
          new VerifyResult.Mismatch(
              p.sheet(),
              p.ref().toA1(),
              FormulaPrinter.print(p.expr()),
              show(p.expected()),
              show(p.actual()),
              outermost(p.expr()),
              MismatchPattern.of(p.expr(), p.expected(), p.actual()),
              !readsWrongCell(p, sheets, wrong)));
    }
  }

  private static boolean readsWrongCell(
      Pending p, List<Sheet> sheets, Map<Integer, Set<CellRef>> wrong) {
    return Exprs.any(
        p.expr(),
        node ->
            switch (node) {
              case Expr.Cell c -> overlaps(p, sheets, wrong, c.scope(), c.at(), c.at());
              case Expr.Area a -> overlaps(p, sheets, wrong, a.scope(), a.from(), a.to());
              default -> false;
            });
  }

  private static boolean overlaps(
      Pending p,
      List<Sheet> sheets,
      Map<Integer, Set<CellRef>> wrong,
      Expr.Scope scope,
      Expr.Endpoint from,
      Expr.Endpoint to) {
    Set<CellRef> cells = wrong.get(sheetIndex(p.sheetIndex(), sheets, scope));
    if (cells == null) {
      return false;
    }
    int left = from.wholeRow() ? 1 : Math.min(from.col(), to.col());
    int right = from.wholeRow() ? CellRef.MAX_COLUMN : Math.max(from.col(), to.col());
    int top = from.wholeColumn() ? 1 : Math.min(from.row(), to.row());
    int bottom = from.wholeColumn() ? CellRef.MAX_ROW : Math.max(from.row(), to.row());
    for (CellRef cell : cells) {
      if (!cell.equals(p.ref())
          && cell.column() >= left
          && cell.column() <= right
          && cell.row() >= top
          && cell.row() <= bottom) {
        return true;
      }
    }
    return false;
  }

  private static int sheetIndex(int home, List<Sheet> sheets, Expr.Scope scope) {
    if (scope.external() || scope.threeD()) {
      return -1;
    }
    if (scope.sheet().isEmpty()) {
      return home;
    }
    for (int i = 0; i < sheets.size(); i++) {
      if (sheets.get(i).name().equalsIgnoreCase(scope.sheet().get())) {
        return i;
      }
    }
    return -1;
  }

  private static void check(
      int sheetIndex,
      String sheet,
      Cell cell,
      ParsedFormula formula,
      WorkbookEvaluator.Outcome outcome,
      VerifyResult result,
      List<Pending> pending,
      Map<Integer, Set<CellRef>> addInCells) {
    switch (outcome.status()) {
      case UNSUPPORTED_FEATURE -> {
        result.add(Category.UNSUPPORTED_FEATURE);
        result.unsupportedFeature(outcome.detail());
        return;
      }
      case UNSUPPORTED_FUNCTION -> {
        result.add(Category.UNSUPPORTED_FUNCTION);
        result.unsupportedFunction(outcome.detail());
        return;
      }
      case VOLATILE -> {
        result.notComparable("volatile");
        return;
      }
      default -> {}
    }
    if (cell.value().isEmpty()) {
      result.notComparable("no cached value");
      return;
    }
    if (isNameError(cell.value().get()) && callsAddIn(formula.expr())) {
      result.notComparable(ADD_IN);
      addInCells.computeIfAbsent(sheetIndex, k -> new HashSet<>()).add(cell.ref());
      return;
    }
    Category category = compare(formula.expr(), cell.value().get(), outcome.value());
    if (category != Category.REAL_MISMATCH) {
      result.add(category);
    }
    if (outcome.usedCachedInput()) {
      result.usedCachedInput(true);
    }
    if (category == Category.REAL_MISMATCH) {
      pending.add(
          new Pending(
              sheetIndex, sheet, cell.ref(), formula.expr(), cell.value().get(), outcome.value()));
    }
  }

  private static Category compare(Expr expr, Value expected, Value actual) {
    Double accuracy =
        expr instanceof Expr.Call call ? SOLVER_ACCURACY.get(call.canonicalName()) : null;
    return accuracy == null
        ? Comparison.compare(expected, actual)
        : Comparison.compareSolver(expected, actual, accuracy);
  }

  private static boolean callsAddIn(Expr expr) {
    return Exprs.functionNames(expr).stream().anyMatch(ANALYSIS_TOOLPAK::contains);
  }

  private static String outermost(Expr expr) {
    List<String> names = Exprs.functionNames(expr);
    return names.isEmpty()
        ? ""
        : new Expr.Call(names.get(0), List.of(), io.github.joshblazer.cellerity.formula.Span.NONE)
            .canonicalName();
  }

  private static String show(Value value) {
    return switch (value) {
      case Value.Num n -> Double.toString(n.value());
      case Value.Text t -> "\"" + t.value() + "\"";
      case Value.Bool b -> b.value() ? "TRUE" : "FALSE";
      case Value.Err e -> e.error().code();
      case Value.Blank b -> "(blank)";
      case Value.Grid g -> {
        try {
          yield "{" + Coercion.toText(g) + "...}";
        } catch (FunctionError e) {
          yield "{array}";
        }
      }
    };
  }
}
