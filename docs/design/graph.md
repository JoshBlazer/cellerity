# Dependency graph and incremental recalculation (M3)

**Goal.** Changing an input recalculates only the formulas that can be affected, and always gives the answer a full
recalculation would give. A wrong "not affected" is a silent wrong answer, so every doubt resolves towards recalculating.

## Rules

1. **Soundness comes first.** For any change, incremental values must equal those of a new `WorkbookEvaluator` with the
   same inputs. This is tested on random workbooks and on every corpus workbook.
2. **Precision comes second.** Recalculating too much is a performance bug. Recalculating too little is a correctness bug.
3. **The evaluator stays the source of truth.** The graph decides what to forget and in what order to ask. It never
   calculates, so the interpreter and Truffle share it.

## Structure

`cellerity-graph` knows formulas and references only. `cellerity-interp` depends on it, and
`IncrementalWorkbook` joins the graph to a `WorkbookEvaluator`.

```java
public record CellKey(int sheet, CellRef at) {}

public final class DependencyGraph {
  public static DependencyGraph build(ParsedWorkbook workbook, NameResolver names);
  public Set<CellKey> formulas();                         // masters and ordinary formulas
  public Set<CellKey> alwaysDirty();                      // recalculated on every change
  public List<CellKey> dependents(CellKey cell);          // formulas that directly read this cell
  public Set<CellKey> affectedBy(Collection<CellKey> changed);
  public List<CellKey> evaluationOrder(Collection<CellKey> dirty); // precedents first, cycles last
  public List<List<CellKey>> cycles();                    // Tarjan, in a stable order
}

public final class IncrementalWorkbook {
  public IncrementalWorkbook(ParsedWorkbook workbook, FormulaEngine engine, VolatilePolicy policy);
  public void set(int sheet, CellRef at, Value value);    // takes effect at the next recalculate
  public Recalculation recalculate();
  public Value value(int sheet, CellRef at);
  public WorkbookEvaluator.Outcome outcome(int sheet, CellRef at);
  public List<List<CellKey>> cycles();
  public long evaluations();
}
```

## Precedents

Read from the AST without evaluating:

| Expression | Precedent |
|---|---|
| Cell, area, whole row or column | Those cells, on the sheet named or the formula's own |
| Defined name | Whatever its expression reads, recursively, shifted by the formula's position as relative names are, with a guard against names that loop |
| Member of an array range | The array's master |
| External, 3D, or broken reference | None. The formula is unsupported, or an error regardless of inputs |

Both branches of a lazy `IF` count as precedents. So a change to an untaken branch recalculates the cell, without
changing its value. This is imprecise and sound.

Areas are indexed per sheet by column, with wide areas in one list, and deduplicated. A running total such as
`SUM($A$1:A5000)` down a column is still linear per lookup. That is acceptable until a benchmark says otherwise.

## What cannot be known statically

These formulas are **always dirty**: they recalculate on every change, along with everything that reads them. Excel treats
`OFFSET` and `INDIRECT` as volatile in the same way.

| Formula | Why |
|---|---|
| `NOW`, `TODAY`, `RAND`, `RANDBETWEEN`, `CELL`, `INFO` | Volatile |
| `OFFSET`, `INDIRECT` | The cells they read depend on values |
| `A1:INDEX(...)`, or a range operator on anything but plain references | The area depends on values |
| `SUMIF` or `AVERAGEIF` whose third argument is not the size of its first | They read cells beyond the reference written, sized from the first argument |

Any of these inside a defined name counts too. A formula that reads an always-dirty cell is recalculated because it
is a dependent.

With `VolatilePolicy.USE_CACHED`, volatile cells keep their cached values. So do unsupported formulas, in any policy.
Their dependents are still recalculated, and `usedCachedInput` keeps saying so.

## Cycles

`cycles()` runs Tarjan's algorithm, iteratively so long chains cannot overflow the stack. It finds **static** cycles,
including self-references. A cycle that passes through an untaken `IF` branch never occurs when evaluating, so the CLI
reports these as "possible cycles". Cycles made at run time through `INDIRECT` or `OFFSET` are invisible to the graph,
and the evaluator's own detection catches them.

Cells in a cycle keep their cached value and report the unsupported feature `cycle`. `evaluationOrder` puts cycle cells
last, in a stable order. Which cell in a cycle is reached first can change which of them is judged cyclic, so the
differential check excludes workbooks with cycles and reports how many.

## Recalculation

```
dirty = affectedBy(changed) ∪ alwaysDirty ∪ what reads alwaysDirty
forget each dirty cell in the evaluator
for each cell in evaluationOrder(dirty): ask the evaluator for it
```

Asking in topological order means the evaluator's recursion stays shallow. Setting a cell that holds a formula overrides
it, and its old precedents are kept. That can only recalculate too much.

## Proof

- **Precision:** `IncrementalWorkbookTest` counts evaluations with `WorkbookEvaluator.evaluations()`.
- **Soundness:** a jqwik property on random workbooks, and `make recalc` on every corpus workbook. That perturbs inputs,
  recalculates, compares every formula cell with a new evaluator, and fails on any disagreement.
