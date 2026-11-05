# Design note: reference interpreter and fidelity harness (M2)

Written before the implementation. Numeric rules and mismatch categories are in `numerics.md`.

## Value model

`Value` gains `Grid`, for ranges and arrays. A grid has 0-based `get(row, col)` that never returns a grid.

- `ArrayGrid`: an in-memory array, from array constants and array calculations.
- `RangeValue`: a live view of a sheet rectangle. It is also how a single-cell reference travels. Whole
  columns stay cheap, because iteration stops at the sheet's used bounds.

## Why a reference is not its value

Excel treats the same content differently depending on how it arrived:

| Expression | Result | Why |
|---|---|---|
| `SUM("3")` | 3 | A direct text argument is coerced |
| `SUM(A1)`, A1 holds text `3` | 0 | Text in a reference is ignored |
| `SUM(TRUE)` | 1 | A direct boolean is coerced |
| `SUM(A1)`, A1 holds `TRUE` | 0 | Booleans in a reference are ignored |
| `=A1`, A1 blank | 0 | A blank result becomes 0 |
| `=A1&""`, A1 blank | empty text | Blank coerces to empty text |

So references stay `RangeValue` until an operator or scalar function needs a scalar.

## Scalars from grids

| Context | Single cell | Multi-cell reference | Array constant or computed array |
|---|---|---|---|
| Normal formula | Its value | Implicit intersection with the formula's row or column, else `#VALUE!` | Applied element by element |
| Array formula, or an array-taking argument such as in `SUMPRODUCT` | Its value | Element by element | Element by element |

Broadcasting: a 1-by-n array repeats along rows, an n-by-1 array along columns, and positions outside
both shapes give `#N/A`.

## Function library

Lives in `cellerity-core`, so the interpreter and Truffle share it. **Consequence:** the M4 differential test
checks the two evaluators, not the functions, which only the fidelity harness and unit tests can check.

- Failures use a stack-less `FunctionError` carrying an `ExcelError`, turned into a value at the boundary.
- `IF`, `IFERROR`, `IFNA`, `IFS`, `SWITCH`, and `CHOOSE` are evaluated lazily by the evaluators, so an
  untaken branch cannot cause a cycle or wasted work. Everything else takes evaluated arguments.
- Implementation order follows the corpus ranking in `eval/results/`.

## Evaluation

`Interpreter` is a plain tree walk over one formula, given a `CellSource`. `WorkbookEvaluator` is the simplest
correct whole-workbook driver: recursion with memoization and cycle detection. M3's graph engine must agree
with it.

Defined names resolve local scope first, then global. Their relative references are relative to `A1`, so
they shift by the formula's position.

## Verify

For every formula cell, in order:

1. Parse failures, data tables, external references, 3D references, structured references, and cycles are
   **unsupported feature**.
2. A call to an unimplemented function is **unsupported function**.
3. Volatile functions (`NOW`, `TODAY`, `RAND`, `RANDBETWEEN`, `CELL`, `INFO`) cannot match a saved value, so
   they are **not comparable**. So is a formula with no cached value.
4. Everything else is compared using the categories in `numerics.md`.

Cells in the first three groups contribute their cached value to their dependents, so one unsupported cell
does not hide everything downstream. Such dependents are counted as `usedCachedInput`, so the report shows how
much of the result rests on Excel's own numbers.

### "Not comparable" extends the brief's categories

The brief lists six categories. A saved `NOW()` can never match, and calling it a real mismatch would be false,
while calling it a match would inflate the score. It is counted separately and excluded from fidelity. This is
recorded as an ADR.

### Reporting rules that keep the number honest

- Fidelity is always shown with coverage. A workbook of unsupported formulas must not score 100%.
- Results are split by writer label. **The headline fidelity counts only files labeled as written by Excel**,
  since others are lower-trust.
- A check fails if it examines too little. The harness has a floor on compared cells, as in ADR-0011.

## Honest limits planned for M2

Dynamic arrays and spill, `LET`, `LAMBDA`, iterative calculation, and locale-specific formats are not supported.
`TEXT` covers common number and date formats only.
