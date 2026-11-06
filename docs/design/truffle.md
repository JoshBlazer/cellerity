# Truffle runtime (M4)

**Goal.** A second `FormulaEngine` that a GraalVM JDK can JIT-compile, and that gives the same answer as the interpreter
on the whole corpus. Any disagreement is a bug.

## What is shared, and what is not

The function library, operators, coercion, and evaluator are shared. So the differential checks the *evaluation of the
tree*: references, operators, laziness, and specialization. It cannot find a bug in `SUM`, which is the same code in both.
That is what fidelity against Excel is for.

## Shape

```java
public final class TruffleEngine implements FormulaEngine {
  public Value evaluate(Expr expr, int sheet, int column, int row, boolean arrayFormula, Workspace workspace);
  public long nodes();          // nodes built
  public long delegatedNodes(); // of which hand a subtree to the interpreter
  public static String runtimeName();
}
```

One engine belongs to one `WorkbookEvaluator`.

**Formulas of one shape share a `CallTarget`.** `=A1*2` in B1 and `=A2*2` in B2 are the same formula in Excel's R1C1 view.
With a target per cell, each would run once per recalculation and rarely get hot, so the JIT would hardly engage. Instead,
relative references are distances from the running cell, and nodes read position and home sheet from the frame. A subtree
handed to the interpreter is first shifted from the cell it was built at to the one running it.

| Node | Does |
|---|---|
| Constant | Literals |
| Reference | A cell or area kept as a reference, for functions that need one |
| Scalar cell | A single cell read as a value, where an operator wants one and it is not an array formula |
| Arithmetic | `+ - * / ^`, specialized on two numbers, and falling back when it sees anything else |
| Comparison, concatenation, unary, percent | The shared operators |
| Call | Evaluates arguments, then calls the shared function library behind a `@TruffleBoundary` |
| If | Lazy, with branch profiles. An array condition hands the whole `IF` to the interpreter |
| Final add or subtract | The root-only snap to zero (ADR-0016) |
| Delegate | Hands a subtree to the interpreter |

**Delegate is deliberate.** Defined names, array constants, the range and intersection operators, the other lazy functions,
and anything unsupported go to the interpreter. These are rarely hot, and sharing them cannot disagree. It does limit what
the JIT can see, so `delegatedNodes` is measured and reported. Growing real nodes is later work, driven by benchmarks.

## Specialization

Hand-written, using `@CompilationFinal` state and `transferToInterpreterAndInvalidate`, not the Truffle DSL. There is one
real specialization and no annotation processor to keep working with `-Werror` and Error Prone (ADR-0018).

Values stay boxed as `Value` records, since every node returns `Value`. Partial evaluation can remove some of that, and
benchmarks will say how much. A `double`-returning execute path is a possible optimization, not part of M4.

## Without a `TruffleLanguage`

The nodes use `new RootNode(null)`, with no language and no polyglot `Context`. Cellerity is not a guest language, and
does not need interop or instruments. Whether this compiles on GraalVM CE has to be shown, not assumed, so `TruffleProbe`
runs on GraalVM CE in CI with `-Dpolyglot.engine.TraceCompilation=true`, on the class path and on the module path.

## Paths

- **GraalVM CE JDK:** optimizing runtime, if the probe shows it compiling.
- **Stock OpenJDK and Java 21:** Truffle's fallback runtime, with no JIT. It gives correct answers and is an extra layer over
  the interpreter, so the CLI's default stays the interpreter unless benchmarks say otherwise (ADR-0006).

## Proof

- `TruffleDifferentialTest`: every line of `function-cases.tsv` in both engines, plus specialization changes, long chains,
  cycles, and arrays.
- `make differential`: every formula cell of every corpus workbook, comparing value and status, on Temurin in `eval.yml` and
  on GraalVM CE in `bench.yml`.
- JMH against Apache POI, in `docs/benchmarks/`.
