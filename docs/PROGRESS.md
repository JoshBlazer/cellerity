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

1. **Visibility:** the repository starts private. When should it become public?
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
