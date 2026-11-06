# Progress

Read this first at the start of every session. Append at the end of every session.

---

## Session 1: 2026-11-04 to 2026-11-05

### Done

- Name check: free on Maven Central. The GitHub handle `Cellerity` is taken by a dormant account, so the
  maintainer chose `JoshBlazer/cellerity` and `io.github.joshblazer` (ADR-0001).
- Scaffolded 10 modules plus `eval/`, with a Gradle wrapper, Spotless, Error Prone, and CI workflows.
- Wrote PRODUCT, ARCHITECTURE, DECISIONS (ADR-0001 to ADR-0012), ROADMAP, RISKS, CLAUDE.md, README, and
  design notes for numerics and the reader.
- Added a corpus manifest of 353 POI test files with checksums, 4 POI samples, and a synthetic sample.
- Implemented `cellerity-core` basics, the `cellerity-io` reader, `cellerity eval` and `inspect`,
  `CorpusLoad`, and `PoiCrossCheck`.

### Proven (locally; see `docs/walkthroughs/m0.md`)

- `./gradlew clean build`: 160 tests pass on Temurin 25.0.3 and 21.0.13, Windows 11.
- M0 exit check 1: `cellerity eval sample.xlsx --get Sheet1!A1` prints `42`.
- M0 exit check 2: 358 files, 332 loaded, 26 rejected, 0 crashed.
- POI cross-check: 320 files, 1,120,879 cells, 0 disagreements, corpus unchanged afterwards.
- Stock Temurin 25 gets the interpreted Truffle runtime (ADR-0006).

- CI is green on `0f757b5`: `build` on Ubuntu with JDK 21 and 25 and on Windows with JDK 25, and `eval`
  on Ubuntu with JDK 25. The eval log matches the Windows numbers exactly.

### Not proven

- Fidelity to Excel: there is no evaluator yet.
- Truffle JIT on a GraalVM JDK: only the fallback on stock Temurin was run (ADR-0006).
- `make` has not run on the Windows development machine, where it is not installed. It runs in CI.

### Next

1. Maintainer review of M0.
2. M1: write the parser design note with signatures and failing tests first. Expand shared formulas,
   and add parse rate and regression gates to `make eval`.

### Open questions for the maintainer

1. *Resolved:* the repository is public, at the maintainer's request.
2. *Resolved:* the real-world corpus is cleaned Enron read with POI HSSF inside `eval/` (ADR-0013).
3. **Trademarks (before M6):** two "cellerity" US filings appeared in a search and could not be opened.
4. **Tagline:** JIT needs a GraalVM JDK, so standard-JDK teams do not get the headline speed (R7).

### Blockers

None.

### Working notes

- Commit and push only as `Joshua Willie <joshblazerwillie@gmail.com>` through the `JoshBlazer` account,
  with no co-authors unless explicitly requested. The repo has local identity and credential settings,
  and the first 6 commits were rewritten before the first push to match.
- In this Windows Git Bash setup, heredocs containing `#` or apostrophes can fail to parse, and a native
  `curl` cannot read POSIX paths from a config file.
- The first `gradlew` wrapper download stalled at 0 bytes here. The distribution was seeded into the
  wrapper cache after verifying its SHA-256. `curl` fetched the same file normally, and CI is unaffected
  unless it reproduces there.
- Never open corpus files with `new XSSFWorkbook(File)`: it writes to them on close (ADR-0011).

## 2026-11-05: M1

The maintainer asked for M1 to M4 without a stop at each milestone, and for the repository to be public.

### Done and proven

- Parser, printer, shifter, shared formulas: 261 tests pass (`./gradlew build`).
- Enron: 831,391 of 831,391 formula cells parse, 108 data tables are excluded, 164 of 616 files are unreadable.
- POI: 27,785 of 27,785 formula cells parse.
- `make eval` gates each corpus against `eval/baseline-*.properties`.

Limits are in `docs/walkthroughs/m1.md`.

### Working notes

- The machine has 16 GB and little free memory. A background job was stopped by the memory guard. Run one
  build at a time, with `--no-daemon --max-workers=2`. The `eval` run is capped at a 2 GB heap.
- `download.sh` starts several processes per file and failed under memory pressure on Windows. Worth batching.
- Always give `gh` `</dev/null` and a timeout, since it can hang waiting on input here.

### Next

M2: reference interpreter and fidelity harness.

## 2026-11-06: M2

### Done and proven

- Reference interpreter and function library. CLI `verify`, `coverage`, `eval --set`. Static report.
- Enron: 99.9739% of 340,722 compared, coverage 48.21% of 706,765 (CI run 42241323335). POI: 96.4393%, coverage 84.97%.
- `make fidelity` gates each corpus against `eval/baseline-fidelity-*.properties`.
- CI green on `64768f6`: `build` run 42241919507 (Ubuntu 21 and 25, Windows 25) and `eval` run 42241919496.

Limits are in `docs/walkthroughs/m2.md`.

### Working notes

- The memory guard stopped the local Enron run twice. The maintainer chose to run corpus jobs on GitHub Actions.
  They take under a minute there. Only compile and unit tests run locally, one at a time.
- CI logs and artifacts are public. Enron output must stay aggregate. `--detail` is never passed for Enron on CI.
- Work happens on `milestone/*` branches, which CI also builds, so `main` stays green.

### Next

M3: dependency graph and incremental recalculation.

## 2026-11-06: M3

### Done and proven

- Dependency graph, incremental recalculation, and cycle reporting. Limits are in `docs/walkthroughs/m3.md`.
- `make recalc`: 0 disagreements in 2,533,277 cell comparisons after 1,099 changes, 6.24% of full evaluations.
- CI green on `7c3ec48`: `build` run 42246679913 and `eval` run 42246679979.

### Process notes

- The M3 tests ran red first (36 of 36), but the stubs were replaced before they were committed, so no separate commit shows it.
- `196945c` reached the branch with a failing test, and `67f5bab` has a message claiming a fix that it does not contain. The fix
  is `7c3ec48`. Pushed history was not rewritten. `main` received these only together with the fixes.
- Commands now commit only after checking that the build passed and that patches applied.

### Next

M4: Truffle runtime and benchmarks.

## 2026-11-06: M4

### Done and proven

- Truffle engine. `make differential`: 0 disagreements in 860,666 formula cells, on Temurin and on GraalVM CE (3,677 compilations, 0 failures).
- JMH against POI, published in `docs/benchmarks/README.md`, from CI runs on a shared runner: `bench` 42251602885, `eval` 42251602610 on `02b8ab3`.
- CI green on `2184f2b`: `build` run 42257460414 and `eval` run 42257460486.
- M1 to M4, as the maintainer asked.

### Finding

The JIT gave no measured speed benefit over the interpreter on the one model benchmarked. The lead over POI comes from the evaluator design. This is stated in the README.

### Open questions for the maintainer

- The tagline says "JIT-compiled", which is accurate about the runtime, but it suggests a speed benefit that has not been measured. Keep it, or change it?
- Is further JIT work worthwhile before M5? It would start with a profile, then compiling across cell reads and unboxing numbers.
- Benchmarks on a quiet machine would replace the noisy numbers. This machine has too little free memory to do that today.

### Limits worth remembering

- `cellerity bench` is not implemented. M5 and M6 were not requested.
- Memory here is short, so corpus jobs and benchmarks run on CI.

### Next

Wait for review. M5 is not started.

