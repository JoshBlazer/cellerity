# Architecture (draft)

Only `cellerity-core` (partly), `cellerity-io`, `cellerity-cli` (partly), and `eval` are implemented.
The rest describes the plan and will change as milestones land.

```
 .xlsx ─▶ reader ─▶ workbook model ─▶ formula parser ─▶ AST ─┬─▶ reference interpreter (oracle)
                     (cells, names,                          └─▶ Truffle runtime (fast path)
                      cached values)                                     │
                                       dependency graph ◀────────────────┘
                                       (incremental recalc)
                                               │
                                     API layer / CLI / reports
```

## Modules

| Module | Responsibility | Depends on | State |
|---|---|---|---|
| `cellerity-core` | Value model, Excel errors, coercion, function library | none | Values, errors, cell references, date serials done |
| `cellerity-io` | `.xlsx` reading | core | Done for M0 |
| `cellerity-formula` | Lexer, parser, printer, reference shifting, shared-formula expansion | core, io | Done for M1 |
| `cellerity-interp` | Plain tree-walking reference interpreter, the oracle | core, formula | M2 |
| `cellerity-graph` | Precedents and dependents, topological evaluation, dirty marking, cycles, volatile functions | core, formula | M3 |
| `cellerity-truffle` | Truffle nodes with type specialization; must match the interpreter | core, formula | M4 |
| `cellerity-api` | Runtime API and typed API generation | io, interp, graph | M5 |
| `cellerity-cli` | Command line | io, core (more later) | `eval` and `inspect` for M0 |
| `cellerity-report` | Static HTML reports, offline | io | M2 |
| `cellerity-bench` | JMH benchmarks versus Apache POI | api | M4 |
| `cellerity-eval` (`eval/`) | Corpus harness and results history; not published | io, core, POI read-only (ADR-0011) | Load check and POI cross-check for M0 |

Dependencies point one way, and no module depends on a CLI or report module. Module dependencies will
change as the milestones arrive, and each change is reflected in this table.

## Principles

- **Interpreter first, Truffle second.** The interpreter is the oracle, and any disagreement between
  the two on the corpus is a bug.
- **Reader preserves, evaluator interprets.** `cellerity-io` applies no Excel semantics beyond decoding
  storage. Formula text and cached values come through unchanged.
- **Typed failure.** Library code signals expected failures with typed exceptions. Exit codes are
  decided only in the CLI.
- **Measured claims.** Eval results are committed to `eval/results/`, and reports show gaps next to
  scores.

## Key design documents

- `docs/design/reader.md`: reader API, read sequence, security
- `docs/design/numerics.md`: doubles, dates, rounding, mismatch categories
- `docs/formats/xlsx-subset.md`: which SpreadsheetML is read, and the corpus file formats
- `docs/DECISIONS.md`: decision records
