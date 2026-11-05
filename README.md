# Cellerity

**Run your Excel model as a JIT-compiled Java service, and prove it matches Excel.**

> **Status: early development.** Cellerity reads workbooks today. It **does not evaluate formulas yet**:
> `cellerity eval` prints the values Excel stored in the file. There are no releases, and none of the
> speed or fidelity goals below has been measured.

Banks, insurers, and pricing teams run critical logic in Excel, and engineers need those models inside
Java systems. Cellerity's goal is to compile a workbook's formulas into a GraalVM Truffle runtime, with
fidelity to Excel measured and published rather than assumed.

JIT compilation will require a GraalVM JDK. On stock OpenJDK, Truffle runs interpreted, which is correct
but slower ([ADR-0006](docs/DECISIONS.md)).

## Status

Every row names the command that proves it. "Proven locally" means the command passed on Windows 11 on
Java 25, and on Java 21 where stated. CI has not run yet, because the repository has not been pushed.

| Capability | State | Proving command |
|---|---|---|
| Build, formatting, static analysis, tests (160) on Java 21 and 25 | Proven locally | `./gradlew clean build` |
| Group ID in a single build property | Proven locally | `git grep -n "io.github.joshblazer" -- "*.kts" "*.toml" "*.properties"` |
| Read sheets, cells, formulas, cached values, defined names | Proven locally | `cellerity inspect corpus/samples/synthetic/sample.xlsx` |
| Print a cached value (**M0 exit check**) | Proven locally | `cellerity eval corpus/samples/synthetic/sample.xlsx --get 'Sheet1!A1'` |
| Corpus loads with no crashes (**M0 exit check**) | Proven locally | `make eval`: 358 files, 332 loaded, 26 rejected, 0 crashed |
| Reader agrees with Apache POI on stored values and formula text | Proven locally | `make crosscheck`: 320 files, 1,120,879 cells, 0 disagreements |
| Corpus integrity | Proven locally | `corpus/download.sh --verify-only`: 353 verified |
| CI green | **Not yet run** | `.github/workflows/` |
| **M0 complete** | **Pending CI** | |
| Formula parsing (M1) | Not started | |
| Formula evaluation, `verify`, `coverage`, fidelity (M2) | Not started | |
| Incremental recalculation (M3) | Not started | |
| Truffle runtime, benchmarks (M4) | Not started | |
| Typed API generation (M5) | Not started | |
| Public fidelity and benchmark pages (M6) | Not started | |

## Honest limits today

- **No formula evaluation.** `--set` exits with code `1` rather than print a stale value.
- **`.xlsx` and `.xlsm` only.** Legacy `.xls`, encrypted files, and `.xlsb` are rejected.
- **The corpus is not real-world.** It is Apache POI's test data, mostly bug reproductions and fuzzer
  output. The POI cross-check shows that our reader decodes files as another mature reader does. It does
  not show fidelity to Excel, and a real-world corpus is still an open decision
  ([ADR-0008](docs/DECISIONS.md)).
- **Some files are strictly rejected.** A file POI can read may be rejected here if a value would have to
  be guessed ([ADR-0012](docs/DECISIONS.md)).
- **Not everything was cross-checked.** POI could not read 7 of the files we load, including Strict OOXML.
- **Workbooks are held fully in memory.** Very large files are limited by heap and a 20-million-cell cap.
- **`make` has not been run locally,** because it is not installed on the development machine. The
  commands in its `eval` and `crosscheck` recipes were run directly.

## Try it

Needs Java 21 or newer.

```
./gradlew :cellerity-cli:installDist
alias cellerity=cellerity-cli/build/install/cellerity/bin/cellerity

cellerity eval corpus/samples/synthetic/sample.xlsx --get 'Sheet1!A1'     # 42
cellerity eval corpus/samples/synthetic/sample.xlsx --get CoverAmount     # 50000
cellerity inspect corpus/samples/poi/shared_formulas.xlsx
```

Exit codes: `0` clean, `1` mismatches or unsupported features, `2` Cellerity error.

Corpus checks need `make` and network access for the first download:

```
make eval
make crosscheck
```

## Coordinates

Group ID `io.github.joshblazer`, packages under `io.github.joshblazer.cellerity`
([ADR-0001](docs/DECISIONS.md)). Nothing is published to Maven Central yet.

## Documentation

[Product](docs/PRODUCT.md) · [Architecture](docs/ARCHITECTURE.md) · [Decisions](docs/DECISIONS.md) ·
[Roadmap](docs/ROADMAP.md) · [Risks](docs/RISKS.md) · [Progress](docs/PROGRESS.md) ·
[M0 walkthrough](docs/walkthroughs/m0.md) · [Numerics](docs/design/numerics.md) ·
[Reader design](docs/design/reader.md)

## AI-assistance disclosure

Cellerity is built with substantial AI assistance. The maintainer wrote the project brief and made the
decisions marked as maintainer decisions in the ADRs. Claude (Anthropic) wrote all code, tests, and
documentation so far under that brief.

**No module has yet begun from hand-written design notes or tests.** The design notes and failing tests
for `cellerity-core` and `cellerity-io` came first, as the brief requires, but they were AI-written too.
This section will name any module that starts from hand-written work.

## License

[Apache-2.0](LICENSE). Sample attribution is in [NOTICE](NOTICE).
