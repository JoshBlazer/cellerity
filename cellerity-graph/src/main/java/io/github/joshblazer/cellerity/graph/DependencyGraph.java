package io.github.joshblazer.cellerity.graph;

import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.functions.Functions;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.Exprs;
import io.github.joshblazer.cellerity.formula.ParsedFormula;
import io.github.joshblazer.cellerity.formula.ParsedWorkbook;
import io.github.joshblazer.cellerity.io.Sheet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Which formulas read which cells, read from the AST. See {@code docs/design/graph.md}. */
public final class DependencyGraph {
  /** The cells these read depend on values, as do those of the volatile functions. */
  private static final Set<String> DYNAMIC = Set.of("OFFSET", "INDIRECT");

  /** These read a range sized from their first argument, whatever the third one says. */
  private static final Set<String> RESIZING = Set.of("SUMIF", "AVERAGEIF");

  private static final int WIDE_AREA = 64;

  private record Area(int left, int top, int right, int bottom) {
    boolean contains(CellRef cell) {
      return cell.column() >= left
          && cell.column() <= right
          && cell.row() >= top
          && cell.row() <= bottom;
    }
  }

  /** The areas read on one sheet. Narrow ones are found by column, and wide ones by scanning. */
  private static final class AreaIndex {
    final Map<Area, List<CellKey>> readers = new HashMap<>();
    final Map<Integer, List<Area>> byColumn = new HashMap<>();
    final List<Area> wide = new ArrayList<>();

    void add(Area area, CellKey reader) {
      List<CellKey> list = readers.get(area);
      if (list == null) {
        list = new ArrayList<>();
        readers.put(area, list);
        if (area.right() - area.left() >= WIDE_AREA) {
          wide.add(area);
        } else {
          for (int c = area.left(); c <= area.right(); c++) {
            byColumn.computeIfAbsent(c, k -> new ArrayList<>()).add(area);
          }
        }
      }
      list.add(reader);
    }

    void collect(CellRef cell, Collection<CellKey> out) {
      for (Area area : byColumn.getOrDefault(cell.column(), List.of())) {
        if (area.contains(cell)) {
          out.addAll(readers.get(area));
        }
      }
      for (Area area : wide) {
        if (area.contains(cell)) {
          out.addAll(readers.get(area));
        }
      }
    }
  }

  private final Set<CellKey> formulas = new TreeSet<>();
  private final Set<CellKey> alwaysDirty = new TreeSet<>();
  private final Map<CellKey, List<CellKey>> singleReaders = new HashMap<>();
  private final List<AreaIndex> areas = new ArrayList<>();
  private final Map<CellKey, List<CellKey>> arrayMembers = new HashMap<>();
  private final Map<String, Integer> sheetsByName = new HashMap<>();

  private DependencyGraph() {}

  /** Builds the graph. Nothing is evaluated. */
  public static DependencyGraph build(ParsedWorkbook workbook, NameResolver names) {
    DependencyGraph graph = new DependencyGraph();
    List<Sheet> sheets = workbook.workbook().sheets();
    for (int s = 0; s < sheets.size(); s++) {
      graph.sheetsByName.putIfAbsent(lower(sheets.get(s).name()), s);
      graph.areas.add(new AreaIndex());
    }
    for (int s = 0; s < sheets.size(); s++) {
      int sheet = s;
      workbook.formulas(s).forEach((at, formula) -> graph.addFormula(sheet, at, formula, names));
      workbook
          .arrayMembers(s)
          .forEach((member, master) ->
              graph
                  .arrayMembers
                  .computeIfAbsent(new CellKey(sheet, master), k -> new ArrayList<>())
                  .add(new CellKey(sheet, member)));
    }
    return graph;
  }

  private static String lower(String text) {
    return text.toLowerCase(java.util.Locale.ROOT);
  }

  private void addFormula(int sheet, CellRef at, ParsedFormula formula, NameResolver names) {
    CellKey reader = new CellKey(sheet, at);
    formulas.add(reader);
    if (formula.ok() && formula.kind() != ParsedFormula.Kind.DATA_TABLE) {
      read(formula.expr(), sheet, reader, names, new HashSet<>());
    }
  }

  private void read(
      Expr expr, int fromSheet, CellKey reader, NameResolver names, Set<String> namesSeen) {
    switch (expr) {
      case Expr.Cell cell ->
          target(cell.scope(), fromSheet)
              .ifPresent(sheet ->
                  singleReaders
                      .computeIfAbsent(
                          new CellKey(sheet, new CellRef(cell.at().col(), cell.at().row())),
                          k -> new ArrayList<>())
                      .add(reader));
      case Expr.Area area ->
          target(area.scope(), fromSheet)
              .ifPresent(sheet -> areas.get(sheet).add(bounds(area), reader));
      case Expr.Name name -> {
        int from = target(name.scope(), fromSheet).orElse(fromSheet);
        if (namesSeen.add(from + "!" + lower(name.name()))) {
          names
              .resolve(name.name(), from)
              .ifPresent(body -> read(body, from, reader, names, namesSeen));
        }
      }
      case Expr.Call call -> {
        String function = call.canonicalName();
        if (Functions.VOLATILE.contains(function)
            || DYNAMIC.contains(function)
            || (RESIZING.contains(function) && readsBeyondItsThirdArgument(call))) {
          alwaysDirty.add(reader);
        }
        call.args().forEach(arg -> read(arg, fromSheet, reader, names, namesSeen));
      }
      case Expr.Binary binary -> {
        if (binary.op() == Expr.BinaryOp.RANGE) {
          alwaysDirty.add(reader);
        }
        read(binary.left(), fromSheet, reader, names, namesSeen);
        read(binary.right(), fromSheet, reader, names, namesSeen);
      }
      default ->
          Exprs.children(expr).forEach(child -> read(child, fromSheet, reader, names, namesSeen));
    }
  }

  private static boolean readsBeyondItsThirdArgument(Expr.Call call) {
    if (call.args().size() < 3 || call.args().get(2) instanceof Expr.Missing) {
      return false;
    }
    Optional<int[]> first = size(call.args().get(0));
    Optional<int[]> third = size(call.args().get(2));
    return first.isEmpty()
        || third.isEmpty()
        || first.get()[0] != third.get()[0]
        || first.get()[1] != third.get()[1];
  }

  private static Optional<int[]> size(Expr expr) {
    return switch (expr) {
      case Expr.Cell c -> Optional.of(new int[] {1, 1});
      case Expr.Area a -> {
        Area area = bounds(a);
        yield Optional.of(
            new int[] {area.right() - area.left() + 1, area.bottom() - area.top() + 1});
      }
      default -> Optional.empty();
    };
  }

  private static Area bounds(Expr.Area area) {
    Expr.Endpoint from = area.from();
    Expr.Endpoint to = area.to();
    boolean wholeRow = from.wholeRow() || to.wholeRow();
    boolean wholeColumn = from.wholeColumn() || to.wholeColumn();
    return new Area(
        wholeRow ? 1 : Math.min(from.col(), to.col()),
        wholeColumn ? 1 : Math.min(from.row(), to.row()),
        wholeRow ? CellRef.MAX_COLUMN : Math.max(from.col(), to.col()),
        wholeColumn ? CellRef.MAX_ROW : Math.max(from.row(), to.row()));
  }

  /** The sheet a reference points at, or empty if it is outside what the graph can follow. */
  private Optional<Integer> target(Expr.Scope scope, int fromSheet) {
    if (scope.external() || scope.threeD()) {
      return Optional.empty();
    }
    return scope.sheet().isEmpty()
        ? Optional.of(fromSheet)
        : Optional.ofNullable(sheetsByName.get(lower(scope.sheet().get())));
  }

  /** Every formula: ordinary cells and array masters, not array members. */
  public Set<CellKey> formulas() {
    return Collections.unmodifiableSet(formulas);
  }

  /** Formulas whose reads cannot be known without evaluating, so they recalculate on any change. */
  public Set<CellKey> alwaysDirty() {
    return Collections.unmodifiableSet(alwaysDirty);
  }

  /** The formulas that directly read a cell, in a stable order. */
  public List<CellKey> dependents(CellKey cell) {
    Set<CellKey> readers = new TreeSet<>();
    collectReaders(cell, readers);
    return List.copyOf(readers);
  }

  private void collectReaders(CellKey cell, Collection<CellKey> out) {
    List<CellKey> singles = singleReaders.get(cell);
    if (singles != null) {
      out.addAll(singles);
    }
    if (cell.sheet() < areas.size()) {
      areas.get(cell.sheet()).collect(cell.at(), out);
    }
  }

  /** What a formula's result reaches: readers of its own cell and of its array members. */
  private Set<CellKey> readersOfFormula(CellKey formula) {
    Set<CellKey> readers = new TreeSet<>();
    collectReaders(formula, readers);
    for (CellKey member : arrayMembers.getOrDefault(formula, List.of())) {
      collectReaders(member, readers);
    }
    return readers;
  }

  /**
   * Everything to recalculate after these cells change: the changed cells that are formulas, what
   * reads them, transitively, and every always-dirty formula with what reads it.
   */
  public Set<CellKey> affectedBy(Collection<CellKey> changed) {
    Set<CellKey> affected = new HashSet<>(alwaysDirty);
    Deque<CellKey> work = new ArrayDeque<>(alwaysDirty);
    for (CellKey cell : changed) {
      if (formulas.contains(cell)) {
        if (affected.add(cell)) {
          work.add(cell);
        }
      } else {
        List<CellKey> readers = new ArrayList<>();
        collectReaders(cell, readers);
        for (CellKey reader : readers) {
          if (affected.add(reader)) {
            work.add(reader);
          }
        }
      }
    }
    while (!work.isEmpty()) {
      for (CellKey reader : readersOfFormula(work.poll())) {
        if (affected.add(reader)) {
          work.add(reader);
        }
      }
    }
    return affected;
  }

  /** Orders formulas so that precedents come first. Cells in or after a cycle come last. */
  public List<CellKey> evaluationOrder(Collection<CellKey> dirty) {
    Set<CellKey> left = new HashSet<>(dirty);
    Map<CellKey, Integer> waitingFor = new HashMap<>();
    Map<CellKey, Set<CellKey>> readers = new HashMap<>();
    for (CellKey formula : left) {
      Set<CellKey> within = readersOfFormula(formula);
      within.retainAll(left);
      readers.put(formula, within);
      for (CellKey reader : within) {
        waitingFor.merge(reader, 1, Integer::sum);
      }
    }
    PriorityQueue<CellKey> ready = new PriorityQueue<>();
    for (CellKey formula : left) {
      if (!waitingFor.containsKey(formula)) {
        ready.add(formula);
      }
    }
    List<CellKey> order = new ArrayList<>(left.size());
    while (!ready.isEmpty()) {
      CellKey next = ready.poll();
      order.add(next);
      left.remove(next);
      for (CellKey reader : readers.get(next)) {
        if (waitingFor.merge(reader, -1, Integer::sum) == 0) {
          ready.add(reader);
        }
      }
    }
    if (!left.isEmpty()) {
      // What remains is in a cycle or after one. Tarjan finds components last-first.
      List<List<CellKey>> components = components(new TreeSet<>(left));
      Collections.reverse(components);
      components.forEach(order::addAll);
    }
    return order;
  }

  /** Static cycles, including self-references, in a stable order. */
  public List<List<CellKey>> cycles() {
    List<List<CellKey>> cycles = new ArrayList<>();
    for (List<CellKey> component : components(formulas)) {
      CellKey only = component.get(0);
      if (component.size() > 1 || readersOfFormula(only).contains(only)) {
        cycles.add(component);
      }
    }
    cycles.sort(Comparator.comparing(cycle -> cycle.get(0)));
    return cycles;
  }

  /**
   * Tarjan's strongly connected components within a set of formulas, each sorted, with a component
   * listed before any that reaches it. It keeps its own stack, as chains can be very long.
   */
  private List<List<CellKey>> components(Set<CellKey> within) {
    List<CellKey> nodes = new ArrayList<>(within);
    Map<CellKey, Integer> ids = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      ids.put(nodes.get(i), i);
    }
    int[] index = new int[nodes.size()];
    int[] low = new int[nodes.size()];
    boolean[] onStack = new boolean[nodes.size()];
    int[][] edges = new int[nodes.size()][];
    int[] nextEdge = new int[nodes.size()];
    Deque<Integer> stack = new ArrayDeque<>();
    Deque<Integer> calls = new ArrayDeque<>();
    List<List<CellKey>> out = new ArrayList<>();
    int counter = 0;
    for (int root = 0; root < nodes.size(); root++) {
      if (index[root] != 0) {
        continue;
      }
      calls.push(root);
      while (!calls.isEmpty()) {
        int v = calls.peek();
        if (index[v] == 0) {
          counter++;
          index[v] = counter;
          low[v] = counter;
          stack.push(v);
          onStack[v] = true;
          edges[v] =
              readersOfFormula(nodes.get(v)).stream()
                  .map(ids::get)
                  .filter(java.util.Objects::nonNull)
                  .mapToInt(Integer::intValue)
                  .toArray();
        }
        boolean descended = false;
        while (nextEdge[v] < edges[v].length) {
          int w = edges[v][nextEdge[v]++];
          if (index[w] == 0) {
            calls.push(w);
            descended = true;
            break;
          }
          if (onStack[w]) {
            low[v] = Math.min(low[v], index[w]);
          }
        }
        if (descended) {
          continue;
        }
        calls.pop();
        if (!calls.isEmpty()) {
          low[calls.peek()] = Math.min(low[calls.peek()], low[v]);
        }
        if (low[v] == index[v]) {
          List<CellKey> component = new ArrayList<>();
          int w;
          do {
            w = stack.pop();
            onStack[w] = false;
            component.add(nodes.get(w));
          } while (w != v);
          Collections.sort(component);
          out.add(component);
        }
      }
    }
    return out;
  }
}
