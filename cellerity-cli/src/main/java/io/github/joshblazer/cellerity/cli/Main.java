package io.github.joshblazer.cellerity.cli;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.interp.Category;
import io.github.joshblazer.cellerity.interp.Coverage;
import io.github.joshblazer.cellerity.interp.Interpreter;
import io.github.joshblazer.cellerity.interp.Verifier;
import io.github.joshblazer.cellerity.interp.VerifyResult;
import io.github.joshblazer.cellerity.interp.WorkbookEvaluator;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.DefinedName;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.WorkbookInfo;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import io.github.joshblazer.cellerity.io.XlsxReader;
import io.github.joshblazer.cellerity.report.FidelityReport;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Entry point. Exit codes: 0 clean, 1 mismatches or unsupported features, 2 Cellerity error. */
public final class Main {
  private static final int OK = 0;
  private static final int UNSUPPORTED = 1;
  private static final int ERROR = 2;

  private static final String USAGE =
      """
      usage: cellerity <command> <workbook.xlsx> [options]

        eval      <file> --get REF [--set REF=VALUE ...]   calculate values, after any changes
        inspect   <file>                             list sheets, names, cells, and formulas
        verify    <file> [--report FILE.html]        compare calculated values with Excel's
        coverage  <file>                             share of function calls we implement
        bench     <file>                             planned for M4
        api       <file> --package NAME              planned for M5

      REF is Sheet!A1, 'My Sheet'!A1, or a defined name that refers to a single cell.
      VALUE is a number, TRUE, FALSE, or text.
      Exit codes: 0 clean, 1 mismatches or unsupported features, 2 error.
      """;

  private static final Map<String, String> PLANNED = Map.of("bench", "M4", "api", "M5");

  private static final int MISMATCHES_KEPT = 10_000;
  private static final int MISMATCHES_PRINTED = 20;

  private final PrintStream out;
  private final PrintStream err;

  private Main(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs a command and returns its exit code, without exiting the JVM. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    return new Main(out, err).dispatch(args);
  }

  private int dispatch(String[] args) {
    if (args.length == 0) {
      err.print(USAGE);
      return ERROR;
    }
    String command = args[0];
    if (command.equals("--help") || command.equals("-h") || command.equals("help")) {
      out.print(USAGE);
      return OK;
    }
    if (PLANNED.containsKey(command)) {
      err.println(
          "cellerity " + command + " is not implemented yet; planned for " + PLANNED.get(command));
      return ERROR;
    }
    List<String> rest = List.of(args).subList(1, args.length);
    return switch (command) {
      case "eval" -> eval(rest);
      case "inspect" -> inspect(rest);
      case "verify" -> verify(rest);
      case "coverage" -> coverage(rest);
      default -> {
        err.println("Unknown command: " + command);
        err.print(USAGE);
        yield ERROR;
      }
    };
  }

  private int eval(List<String> args) {
    String file = null;
    List<String> gets = new ArrayList<>();
    List<String> sets = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals("--get") || arg.equals("--set")) {
        if (i + 1 == args.size()) {
          err.println(arg + " needs a value");
          return ERROR;
        }
        (arg.equals("--get") ? gets : sets).add(args.get(++i));
      } else if (file == null && !arg.startsWith("--")) {
        file = arg;
      } else {
        err.println("Unexpected argument: " + arg);
        return ERROR;
      }
    }
    if (file == null || (gets.isEmpty() && sets.isEmpty())) {
      err.println("eval needs a workbook and at least one --get");
      return ERROR;
    }
    Workbook workbook;
    try {
      workbook = XlsxReader.read(Path.of(file));
    } catch (XlsxReadException e) {
      return readFailure(file, e);
    }
    // After a change, a saved NOW() or RAND() is stale, so volatile functions are calculated too.
    WorkbookEvaluator evaluator =
        new WorkbookEvaluator(
            ParsedWorkbook.of(workbook),
            new Interpreter(),
            sets.isEmpty()
                ? WorkbookEvaluator.VolatilePolicy.USE_CACHED
                : WorkbookEvaluator.VolatilePolicy.CALCULATE);
    for (String set : sets) {
      int equals = set.indexOf('=');
      if (equals <= 0) {
        err.println("--set needs REF=VALUE: " + set);
        return ERROR;
      }
      Target target = target(workbook, set.substring(0, equals));
      if (target.exitCode() != OK) {
        err.println(target.problem());
        return target.exitCode();
      }
      evaluator.set(target.sheet(), target.cell(), parseInput(set.substring(equals + 1)));
    }
    boolean changed = !sets.isEmpty();
    List<String> lines = new ArrayList<>();
    for (String get : gets) {
      Target target = target(workbook, get);
      if (target.exitCode() != OK) {
        err.println(target.problem());
        return target.exitCode();
      }
      WorkbookEvaluator.Outcome outcome = evaluator.outcome(target.sheet(), target.cell());
      if (isCachedInstead(outcome.status()) || (changed && outcome.usedCachedInput())) {
        // A cached value here could be silently wrong, so it is refused rather than printed.
        err.println(
            "Cannot calculate "
                + get
                + (outcome.detail().isEmpty() ? "" : ": " + outcome.detail())
                + (outcome.usedCachedInput() ? " (it rests on a cached value)" : ""));
        return UNSUPPORTED;
      }
      String text = evalText(outcome.value());
      lines.add(gets.size() == 1 ? text : get + "=" + text);
    }
    lines.forEach(out::println);
    return OK;
  }

  private static boolean isCachedInstead(WorkbookEvaluator.Status status) {
    return status == WorkbookEvaluator.Status.UNSUPPORTED_FEATURE
        || status == WorkbookEvaluator.Status.UNSUPPORTED_FUNCTION;
  }

  /** As Excel shows a calculated value: numbers to 15 significant digits. */
  private static String evalText(Value value) {
    return value instanceof Value.Num ? Coercion.toText(value) : format(value);
  }

  private static Value parseInput(String text) {
    if (text.equalsIgnoreCase("TRUE") || text.equalsIgnoreCase("FALSE")) {
      return new Value.Bool(text.equalsIgnoreCase("TRUE"));
    }
    OptionalDouble number = Coercion.parseNumber(text);
    return number.isPresent() ? new Value.Num(number.getAsDouble()) : new Value.Text(text);
  }

  private record Target(int exitCode, int sheet, CellRef cell, String problem) {
    static Target found(int sheet, CellRef cell) {
      return new Target(OK, sheet, cell, "");
    }

    static Target failed(int exitCode, String problem) {
      return new Target(exitCode, -1, null, problem);
    }
  }

  private static Target target(Workbook workbook, String ref) {
    if (ref.contains("!")) {
      return targetCell(workbook, ref, ref);
    }
    return workbook
        .definedName(ref, OptionalInt.empty())
        .map(DefinedName::refersTo)
        .map(refersTo -> targetName(workbook, ref, refersTo))
        .orElseGet(() -> Target.failed(ERROR, "Unknown defined name: " + ref));
  }

  private static Target targetName(Workbook workbook, String name, String refersTo) {
    String stripped = refersTo.startsWith("=") ? refersTo.substring(1) : refersTo;
    Target target = stripped.contains("!") ? targetCell(workbook, stripped, name) : null;
    if (target == null || target.exitCode() != OK) {
      return Target.failed(
          UNSUPPORTED,
          "Defined name " + name + " refers to " + refersTo + ", which is not a single cell");
    }
    return target;
  }

  private static Target targetCell(Workbook workbook, String ref, String label) {
    int bang = ref.lastIndexOf('!');
    String sheetName = unquoteSheet(ref.substring(0, bang));
    int sheet = -1;
    for (int i = 0; i < workbook.sheets().size(); i++) {
      if (workbook.sheets().get(i).name().equalsIgnoreCase(sheetName)) {
        sheet = i;
        break;
      }
    }
    if (sheet < 0) {
      return Target.failed(ERROR, "No sheet named '" + sheetName + "' for " + label);
    }
    var cell = CellRef.parse(ref.substring(bang + 1));
    if (cell.isEmpty()) {
      return Target.failed(ERROR, "Not a single cell reference: " + label);
    }
    return Target.found(sheet, cell.get());
  }

  private int verify(List<String> args) {
    String file = null;
    String report = null;
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals("--report") && i + 1 < args.size()) {
        report = args.get(++i);
      } else if (file == null && !arg.startsWith("--")) {
        file = arg;
      } else {
        err.println("Unexpected argument: " + arg);
        return ERROR;
      }
    }
    if (file == null) {
      err.println("verify needs a workbook");
      return ERROR;
    }
    Workbook workbook;
    try {
      workbook = XlsxReader.read(Path.of(file));
    } catch (XlsxReadException e) {
      return readFailure(file, e);
    }
    ParsedWorkbook parsed = ParsedWorkbook.of(workbook);
    VerifyResult r = Verifier.verify(parsed, MISMATCHES_KEPT);
    out.printf(
        Locale.ROOT,
        "fidelity %.4f%% of %d compared; coverage %.2f%% of %d formula cells%n"
            + "exact %d, display-only %d, within tolerance %d, real mismatch %d%n"
            + "unsupported function %d, unsupported feature %d, not comparable %d%n",
        100 * r.fidelity(),
        r.compared(),
        100 * r.coverage(),
        r.total(),
        r.count(Category.EXACT),
        r.count(Category.DISPLAY_ONLY),
        r.count(Category.WITHIN_TOLERANCE),
        r.count(Category.REAL_MISMATCH),
        r.count(Category.UNSUPPORTED_FUNCTION),
        r.count(Category.UNSUPPORTED_FEATURE),
        r.count(Category.NOT_COMPARABLE));
    r.unsupportedFunctions().forEach((name, n) -> out.println("function " + name + ": " + n));
    r.unsupportedFeatures().forEach((name, n) -> out.println("feature " + name + ": " + n));
    r.notComparable().forEach((why, n) -> out.println("not comparable, " + why + ": " + n));
    r.mismatches().stream()
        .limit(MISMATCHES_PRINTED)
        .forEach(m ->
            out.println(
                singleLine(
                    m.sheet()
                        + "!"
                        + m.cell()
                        + " Excel "
                        + m.expected()
                        + ", Cellerity "
                        + m.actual()
                        + (m.root() ? "" : " (downstream)")
                        + ": ="
                        + m.formula())));
    if (report != null) {
      Coverage coverage = new Coverage();
      coverage.add(parsed);
      try {
        Files.writeString(
            Path.of(report),
            FidelityReport.html("Fidelity: " + Path.of(file).getFileName(), r, coverage, true));
      } catch (IOException e) {
        err.println("Cannot write " + report + ": " + e.getMessage());
        return ERROR;
      }
    }
    boolean clean =
        r.count(Category.REAL_MISMATCH) == 0
            && r.count(Category.UNSUPPORTED_FUNCTION) == 0
            && r.count(Category.UNSUPPORTED_FEATURE) == 0;
    return clean ? OK : UNSUPPORTED;
  }

  private int coverage(List<String> args) {
    if (args.size() != 1) {
      err.println("coverage needs exactly one workbook");
      return ERROR;
    }
    Workbook workbook;
    try {
      workbook = XlsxReader.read(Path.of(args.get(0)));
    } catch (XlsxReadException e) {
      return readFailure(args.get(0), e);
    }
    Coverage coverage = new Coverage();
    coverage.add(ParsedWorkbook.of(workbook));
    out.printf(
        Locale.ROOT,
        "function coverage %.2f%%: %d of %d calls%n",
        100 * coverage.rate(),
        coverage.supportedCalls(),
        coverage.totalCalls());
    coverage.missing().forEach((name, n) -> out.println("missing " + name + ": " + n));
    return coverage.missing().isEmpty() ? OK : UNSUPPORTED;
  }

  private static String unquoteSheet(String name) {
    if (name.length() >= 2 && name.startsWith("'") && name.endsWith("'")) {
      return name.substring(1, name.length() - 1).replace("''", "'");
    }
    return name;
  }

  private int inspect(List<String> args) {
    if (args.size() != 1) {
      err.println("inspect needs exactly one workbook");
      return ERROR;
    }
    String file = args.get(0);
    Workbook workbook;
    try {
      workbook = XlsxReader.read(Path.of(file));
    } catch (XlsxReadException e) {
      return readFailure(file, e);
    }
    printSummary(file, workbook);
    for (Sheet sheet : workbook.sheets()) {
      out.printf(
          "Sheet: %s (%s, %s, %d cells)%n",
          sheet.name(),
          sheet.kind().name().toLowerCase(Locale.ROOT),
          sheet.state().name().toLowerCase(Locale.ROOT),
          sheet.cells().size());
      sheet.cells().values().forEach(cell -> out.println("  " + describe(workbook, cell)));
    }
    return OK;
  }

  private void printSummary(String file, Workbook workbook) {
    WorkbookInfo info = workbook.info();
    out.println("File: " + file);
    out.println(
        "Date system: " + (workbook.dateSystem().name().endsWith("1904") ? "1904" : "1900"));
    out.println(
        "Writer: "
            + info.application().orElse("unknown")
            + info.appVersion().map(v -> " (version " + v + ")").orElse(""));
    out.println(
        "Calculation: mode "
            + info.calcMode().orElse("not stated")
            + ", full calculation on load "
            + (info.fullCalcOnLoad() ? "yes" : "no"));
    out.println("Defined names: " + workbook.definedNames().size());
    for (DefinedName name : workbook.definedNames()) {
      String scope =
          name.localSheetIndex().isPresent()
              ? " (sheet " + name.localSheetIndex().getAsInt() + ")"
              : "";
      out.println(
          "  "
              + name.name()
              + " = "
              + name.refersTo()
              + scope
              + (name.hidden() ? " [hidden]" : ""));
    }
  }

  private static String describe(Workbook workbook, Cell cell) {
    String value = cell.value().map(v -> singleLine(format(v))).orElse("(no cached value)");
    if (cell.dateFormatted() && cell.value().orElse(Value.BLANK) instanceof Value.Num num) {
      value += " (date " + workbook.dateSystem().toIso(num.value()) + ")";
    }
    String line = cell.ref().toA1() + "\t" + value;
    return cell.formula().map(f -> line + "\t=" + describe(f)).orElse(line);
  }

  private static String describe(Formula formula) {
    return formula
        .text()
        .map(Main::singleLine)
        .orElseGet(() ->
            "("
                + formula.kind().name().toLowerCase(Locale.ROOT)
                + formula.sharedIndex().stream().mapToObj(i -> " si=" + i).findFirst().orElse("")
                + ")");
  }

  private static String singleLine(String text) {
    return text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }

  private int readFailure(String file, XlsxReadException e) {
    err.println("Cannot read " + file + ": " + e.kind() + ": " + e.getMessage());
    return ERROR;
  }

  private static String format(Value value) {
    return switch (value) {
      case Value.Num num -> formatNumber(num.value());
      case Value.Text text -> text.value();
      case Value.Bool bool -> bool.value() ? "TRUE" : "FALSE";
      case Value.Err error -> error.error().code();
      case Value.Blank blank -> "";
      case Value.Grid grid -> "{array " + grid.rows() + "x" + grid.columns() + "}";
    };
  }

  private static String formatNumber(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }
}
