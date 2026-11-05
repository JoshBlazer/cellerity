# Roadmap

Each milestone ends with its exit check and `docs/walkthroughs/mN.md`. A milestone counts as done only
once CI is green. Work stops at each milestone end for review.

| ID | Milestone | Exit check |
|---|---|---|
| **M0** | **Read workbooks.** Load `.xlsx`, list sheets, cells, formulas, cached values, defined names. | `cellerity eval sample.xlsx --get Sheet1!A1` prints the cached value; corpus loader runs over N files without crashing |
| M1 | **Parser.** Full grammar for v1 scope with error recovery and precise positions. | Parse success rate over the corpus reported by `make eval`; round-trip property tests pass |
| M2 | **Reference interpreter + fidelity harness.** Core value model, errors, top functions by usage. | `cellerity verify` on corpus files reports fidelity; baseline published in `eval/results/` |
| M3 | **Dependency graph + incremental recalc.** | Changing one input recomputes only affected cells (asserted by counting evaluations); cycles detected and reported |
| M4 | **Truffle runtime + benchmarks.** | Differential test: Truffle equals interpreter on the whole corpus; JMH results vs Apache POI published with methodology |
| M5 | **Model-as-API.** Typed API generation from defined names. | Generated `PricingModel` compiles and returns the same results as `cellerity eval` |
| M6 | **Public launch.** Fidelity and benchmark pages on GitHub Pages, README demo, write-up. | Published URLs + walkthrough |

## Later layers

More functions (driven by corpus ranking), dynamic arrays and spill, `LET`/`LAMBDA`, structured
references, parallel recalculation, iterative calculation, and a native-image CLI.
