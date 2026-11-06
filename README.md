# Cellerity

**Run your Excel model as a JIT-compiled Java service, and prove it matches Excel.**

> **Status: early development.** Cellerity calculates workbooks with a reference interpreter and measures the
> result against the values Excel stored. On the Enron corpus, fidelity is 99.97% of the **48.21%** of formula cells it
> could compare ([what that means](docs/walkthroughs/m2.md)). There is no JIT runtime yet, there are no releases,
> and no speed has been measured.

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
| `cellerity verify` reports fidelity with coverage (**M2 exit check**) | Proven | `cellerity verify corpus/samples/poi/VLookupFullColumn.xlsx --report r.html`, run in `build.yml` |
| Enron fidelity, excluding external links (**M2 exit check**) | 99.9739% of 340,722 compared; coverage 48.21% of 706,765 formula cells | `make fidelity-enron`; `eval/results/2026-11-06-m2-fidelity-enron.json` |
| POI fidelity, a torture test and not real-world usage | 96.4393% of 23,956 compared; coverage 84.97% | `make fidelity-poi` |
| Function coverage | 98.36% of Enron's 299,996 calls | `make fidelity-enron` |
| `--set` recalculates | Proven | `cellerity eval corpus/samples/synthetic/sample.xlsx --set CoverAmount=1000 --get 'Sheet1!B2'` prints 35 |
| CI fails if fidelity regresses | Proven | `make fidelity` with `eval/baseline-fidelity-*.properties`; `FidelityBaselineTest` |
| **M2 complete** | **Done, pending maintainer review** | `docs/walkthroughs/m2.md` |
| Incremental recalculation (M3) | Not started | |
| Truffle runtime, benchmarks (M4) | Not started | |
| Typed API generation (M5) | Not started | |
| Public fidelity and benchmark pages (M6) | Not started | |

## Honest limits today

- **Fidelity is measured on what could be compared.** About half of Enron's Excel-labeled formula cells read other
  workbooks, and external links are out of scope. The evaluator was also fixed while looking at this corpus, so a new
  corpus can be expected to score lower. Read [the M2 walkthrough](docs/walkthroughs/m2.md) before quoting a number.
- **Not implemented:** 3D, union, and structured references, data tables, and some functions and `TEXT` formats. These
  are reported as unsupported, or give `#VALUE!`, instead of being guessed.
- **`.xlsx` and `.xlsm` only.** Legacy `.xls`, encrypted files, and `.xlsb` are rejected.
- **Two corpora mean different things.** POI's test data is mostly bug reproductions and fuzzer output, not real-world
  usage. The cleaned Enron subset is real-world but small, old, and `.xls` ([ADR-0013](docs/DECISIONS.md)).
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
cellerity eval corpus/samples/synthetic/sample.xlsx --set CoverAmount=1000 --get 'Sheet1!B2'     # 35
cellerity verify corpus/samples/poi/shared_formulas.xlsx --report report.html
cellerity coverage corpus/samples/poi/shared_formulas.xlsx
cellerity inspect corpus/samples/poi/shared_formulas.xlsx
```

Exit codes: `0` clean, `1` mismatches or unsupported features, `2` Cellerity error.

Corpus checks need `make` and network access for the first download:

```
make eval
make fidelity
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
