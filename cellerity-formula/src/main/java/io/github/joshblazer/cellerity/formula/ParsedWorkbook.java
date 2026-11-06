package io.github.joshblazer.cellerity.formula;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.io.Cell;
import io.github.joshblazer.cellerity.io.Formula;
import io.github.joshblazer.cellerity.io.Sheet;
import io.github.joshblazer.cellerity.io.Workbook;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A workbook with every formula parsed, shared formulas expanded, and array members linked. */
public final class ParsedWorkbook {
  private static final int MAX_ARRAY_MEMBERS = 2_000_000;

  private final Workbook workbook;
  private final List<Map<CellRef, ParsedFormula>> formulas = new ArrayList<>();
  private final List<Map<CellRef, CellRef>> arrayMembers = new ArrayList<>();

  private ParsedWorkbook(Workbook workbook) {
    this.workbook = workbook;
    Map<String, ParseResult> cache = new HashMap<>();
    for (Sheet sheet : workbook.sheets()) {
      Map<CellRef, ParsedFormula> parsed = new LinkedHashMap<>();
      Map<CellRef, CellRef> members = new HashMap<>();
      parseSheet(sheet, cache, parsed, members);
      formulas.add(java.util.Collections.unmodifiableMap(parsed));
      arrayMembers.add(members);
    }
  }

  /** Parses every formula in the workbook. */
  public static ParsedWorkbook of(Workbook workbook) {
    return new ParsedWorkbook(workbook);
  }

  private record Master(CellRef at, ParseResult result) {}

  private static void parseSheet(
      Sheet sheet,
      Map<String, ParseResult> cache,
      Map<CellRef, ParsedFormula> parsed,
      Map<CellRef, CellRef> members) {
    // A follower normally follows its master. The earliest master covers files where it does not.
    Map<Integer, Master> firstMasters = new HashMap<>();
    for (Cell cell : sheet.cells().values()) {
      Formula f = cell.formula().orElse(null);
      if (f != null && f.kind() == Formula.Kind.SHARED && f.text().isPresent()) {
        firstMasters.putIfAbsent(
            f.sharedIndex().orElse(-1), new Master(cell.ref(), parseCached(f.text().get(), cache)));
      }
    }
    Map<Integer, Master> current = new HashMap<>();
    for (Cell cell : sheet.cells().values()) {
      cell.formula()
          .ifPresent(f -> {
            ParsedFormula formula = parseCell(cell.ref(), f, cache, firstMasters, current);
            parsed.put(cell.ref(), formula);
            if (formula.kind() == ParsedFormula.Kind.ARRAY_MASTER) {
              formula.arrayRange().ifPresent(r -> addMembers(cell.ref(), r, members));
            }
          });
    }
  }

  private static ParseResult parseCached(String text, Map<String, ParseResult> cache) {
    return cache.computeIfAbsent(text, FormulaParser::parse);
  }

  private static ParsedFormula parseCell(
      CellRef at,
      Formula f,
      Map<String, ParseResult> cache,
      Map<Integer, Master> firstMasters,
      Map<Integer, Master> current) {
    return switch (f.kind()) {
      case DATA_TABLE ->
          new ParsedFormula(
              ParsedFormula.Kind.DATA_TABLE,
              new Expr.Invalid("dataTable", Span.NONE),
              f.ref(),
              List.of());
      case ARRAY -> {
        ParseResult result = parseText(f, cache);
        yield new ParsedFormula(
            ParsedFormula.Kind.ARRAY_MASTER, result.expr(), f.ref(), result.diagnostics());
      }
      case NORMAL -> {
        ParseResult result = parseText(f, cache);
        yield new ParsedFormula(
            ParsedFormula.Kind.NORMAL, result.expr(), Optional.empty(), result.diagnostics());
      }
      case SHARED -> shared(at, f, cache, firstMasters, current);
    };
  }

  private static ParseResult parseText(Formula f, Map<String, ParseResult> cache) {
    return f.text()
        .map(text -> parseCached(text, cache))
        .orElseGet(() ->
            new ParseResult(
                new Expr.Invalid("", Span.NONE),
                List.of(new Diagnostic("Formula has no text", Span.NONE))));
  }

  private static ParsedFormula shared(
      CellRef at,
      Formula f,
      Map<String, ParseResult> cache,
      Map<Integer, Master> firstMasters,
      Map<Integer, Master> current) {
    int group = f.sharedIndex().orElse(-1);
    if (f.text().isPresent()) {
      Master master = new Master(at, parseCached(f.text().get(), cache));
      current.put(group, master);
      return new ParsedFormula(
          ParsedFormula.Kind.SHARED,
          master.result().expr(),
          Optional.empty(),
          master.result().diagnostics());
    }
    Master master = current.getOrDefault(group, firstMasters.get(group));
    if (master == null) {
      return new ParsedFormula(
          ParsedFormula.Kind.SHARED,
          new Expr.Invalid("", Span.NONE),
          Optional.empty(),
          List.of(new Diagnostic("Shared formula " + group + " has no master", Span.NONE)));
    }
    Expr shifted =
        ReferenceShifter.shift(
            master.result().expr(),
            at.column() - master.at().column(),
            at.row() - master.at().row());
    return new ParsedFormula(
        ParsedFormula.Kind.SHARED, shifted, Optional.empty(), master.result().diagnostics());
  }

  private static void addMembers(CellRef master, String range, Map<CellRef, CellRef> members) {
    int colon = range.indexOf(':');
    Optional<CellRef> first = CellRef.parse(colon < 0 ? range : range.substring(0, colon));
    Optional<CellRef> last = CellRef.parse(colon < 0 ? range : range.substring(colon + 1));
    if (first.isEmpty() || last.isEmpty()) {
      return;
    }
    for (int row = first.get().row(); row <= last.get().row(); row++) {
      for (int column = first.get().column(); column <= last.get().column(); column++) {
        if (members.size() >= MAX_ARRAY_MEMBERS) {
          return;
        }
        CellRef cell = new CellRef(column, row);
        if (!cell.equals(master)) {
          members.put(cell, master);
        }
      }
    }
  }

  public Workbook workbook() {
    return workbook;
  }

  /** The formulas of a sheet by position. Array members are not included. */
  public Map<CellRef, ParsedFormula> formulas(int sheetIndex) {
    return formulas.get(sheetIndex);
  }

  /** Non-master members of array ranges on a sheet, each with its master. */
  public Map<CellRef, CellRef> arrayMembers(int sheetIndex) {
    return java.util.Collections.unmodifiableMap(arrayMembers.get(sheetIndex));
  }

  /** The master cell of the array range containing this cell, if it is a non-master member. */
  public Optional<CellRef> arrayMaster(int sheetIndex, CellRef cell) {
    return Optional.ofNullable(arrayMembers.get(sheetIndex).get(cell));
  }
}
