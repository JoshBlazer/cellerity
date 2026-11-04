package io.github.joshblazer.cellerity.cli;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.DefinedName;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import io.github.joshblazer.cellerity.io.WorkbookInfo;
import io.github.joshblazer.cellerity.io.XlsxReadException;
import io.github.joshblazer.cellerity.io.XlsxReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/** Entry point. Exit codes: 0 clean, 1 mismatches or unsupported features, 2 Cellerity error. */
public final class Main {
  private static final int OK = 0;
  private static final int UNSUPPORTED = 1;
  private static final int ERROR = 2;

  private static final String USAGE =
      """
      usage: cellerity <command> <workbook.xlsx> [options]

        eval      <file> --get REF [--get REF ...]   print cached values (no recalculation until M2)
        inspect   <file>                             list sheets, names, cells, and formulas
        verify    <file>                             planned for M2
        coverage  <file>                             planned for M2
        bench     <file>                             planned for M4
        api       <file> --package NAME              planned for M5

      REF is Sheet!A1, 'My Sheet'!A1, or a defined name that refers to a single cell.
      Exit codes: 0 clean, 1 unsupported feature, 2 error.
      """;

  private static final Map<String, String> PLANNED =
      Map.of("verify", "M2", "coverage", "M2", "bench", "M4", "api", "M5");

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
    if (!sets.isEmpty()) {
      // Printing a cached value after a --set would be silently wrong (ADR-0010).
      err.println("--set needs recalculation, which is planned for M2");
      return UNSUPPORTED;
    }
    Workbook workbook;
    try {
      workbook = XlsxReader.read(Path.of(file));
    } catch (XlsxReadException e) {
      return readFailure(file, e);
    }
    List<String> lines = new ArrayList<>();
    for (String get : gets) {
      Lookup lookup = lookup(workbook, get);
      if (lookup.exitCode() != OK) {
        err.println(lookup.problem());
        return lookup.exitCode();
      }
      lines.add(gets.size() == 1 ? lookup.text() : get + "=" + lookup.text());
    }
    lines.forEach(out::println);
    return OK;
  }

  private record Lookup(int exitCode, String text, String problem) {
    static Lookup found(String text) {
      return new Lookup(OK, text, "");
    }

    static Lookup failed(int exitCode, String problem) {
      return new Lookup(exitCode, "", problem);
    }
  }

  private static Lookup lookup(Workbook workbook, String ref) {
    if (ref.contains("!")) {
      return lookupCell(workbook, ref, ref);
    }
    return workbook
        .definedName(ref, OptionalInt.empty())
        .map(DefinedName::refersTo)
        .map(target -> lookupName(workbook, ref, target))
        .orElseGet(() -> Lookup.failed(ERROR, "Unknown defined name: " + ref));
  }

  private static Lookup lookupName(Workbook workbook, String name, String target) {
    String stripped = target.startsWith("=") ? target.substring(1) : target;
    Lookup lookup = stripped.contains("!") ? lookupCell(workbook, stripped, name) : null;
    if (lookup == null || lookup.exitCode() != OK) {
      return Lookup.failed(
          UNSUPPORTED,
          "Defined name "
              + name
              + " refers to "
              + target
              + ", which is not a single cell; evaluating it is planned for M2");
    }
    return lookup;
  }

  private static Lookup lookupCell(Workbook workbook, String ref, String label) {
    int bang = ref.lastIndexOf('!');
    String sheetName = unquoteSheet(ref.substring(0, bang));
    var sheet = workbook.sheet(sheetName);
    if (sheet.isEmpty()) {
      return Lookup.failed(ERROR, "No sheet named '" + sheetName + "' for " + label);
    }
    var cellRef = CellRef.parse(ref.substring(bang + 1));
    if (cellRef.isEmpty()) {
      return Lookup.failed(ERROR, "Not a single cell reference: " + label);
    }
    var cell = sheet.get().cell(cellRef.get());
    if (cell.isEmpty()) {
      return Lookup.found("");
    }
    return cell.get()
        .value()
        .map(value -> Lookup.found(format(value)))
        .orElseGet(() ->
            Lookup.failed(
                UNSUPPORTED,
                "Cell "
                    + label
                    + " has a formula but no cached value; evaluating it is planned for M2"));
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
    };
  }

  private static String formatNumber(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }
}
