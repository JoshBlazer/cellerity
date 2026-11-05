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

Every row names the command that proves it. CI runs the build on Ubuntu with Java 21 and 25 and on Windows
with Java 25, and runs the corpus checks on Ubuntu with Java 25.

| Capability | State | Proving command |
|---|---|---|
| Build, formatting, static analysis, tests (261) on Java 21 and 25 | Proven | `./gradlew clean build` |
| Group ID in a single build property | Proven | `git grep -n "io.github.joshblazer" -- "*.kts" "*.toml" "*.properties"` |
| Read sheets, cells, formulas, cached values, defined names | Proven | `cellerity inspect corpus/samples/synthetic/sample.xlsx` |
| Print a cached value (**M0 exit check**) | Proven | `cellerity eval corpus/samples/synthetic/sample.xlsx --get 'Sheet1!A1'` |
| Corpus loads with no crashes (**M0 exit check**) | Proven | `make eval`: 358 files, 332 loaded, 26 rejected, 0 crashed |
| Reader agrees with Apache POI on stored values and formula text | Proven | `make crosscheck`: 320 files, 1,120,879 cells, 0 disagreements |
| Corpus integrity | Proven | `corpus/download.sh --verify-only`: 969 verified |
| CI green | Proven | `.github/workflows/`: `build` and `eval` |
| **M0 complete** | **Done, pending maintainer review** | `docs/walkthroughs/m0.md` |
| Formula parsing, real-world corpus (**M1 exit check**) | Proven | `make eval`: Enron 831,391 of 831,391 and POI 27,785 of 27,785 formula cells parse, with limits in `docs/walkthroughs/m1.md` |
| Round-trip property tests (**M1 exit check**) | Proven | `./gradlew :cellerity-formula:test` |
| CI fails if the parse rate regresses | Proven | `make eval` with `eval/baseline-*.properties` |
| **M1 complete** | **Done, pending maintainer review** | `docs/walkthroughs/m1.md` |
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
  not show fidelity to Excel. The real-world corpus is decided but not built yet
  ([ADR-0013](docs/DECISIONS.md)).
- **Some files are strictly rejected.** A file POI can read may be rejected here if a value would have to
  be guessed ([ADR-0012](docs/DECISIONS.md)).
- **Not everything was cross-checked.** POI could not read 7 of the files we load, including Strict OOXML.
- **Workbooks are held fully in memory.** Very large files are limited by heap and a 20-million-cell cap.

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
