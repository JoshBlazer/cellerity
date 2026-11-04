# Cellerity: working agreement

Run Excel models as JIT-compiled Java services, with Excel fidelity measured and published.

## Start of every session

1. Read `docs/PROGRESS.md`, this file, and the current milestone in `docs/ROADMAP.md`.
2. Check `docs/DECISIONS.md` before re-deciding anything.

## House rules

- **"Done" means a command proves it.** Every row in the README status table names its proving command
  or test, and is updated in the same commit as the work. Never claim something works without running
  the command and showing the output.
- **Design notes before code** for hard modules (parser, dependency graph, Truffle nodes, numerics),
  with signatures and failing tests first. Put them in `docs/design/`.
- **ADR for every non-obvious choice** and every new runtime dependency, in `docs/DECISIONS.md`. Prefer
  the standard library.
- **Honest limits.** Report coverage and fidelity as measured, gaps included. Benchmarks state hardware
  and methodology and use JMH only. Never overclaim. JIT claims must say a GraalVM JDK is required
  (ADR-0006).
- **Interpreter first, Truffle second.** Any disagreement on the corpus is a bug.
- **Ground truth is Excel's cached values.** Label anything else, including LibreOffice output and
  synthetic samples, as lower-trust. POI test data is not real-world usage (ADR-0008).
- If a decision is irreversible or contradicts the brief, stop and ask. Otherwise decide, record an ADR,
  and continue.
- **Stop at the end of each milestone**, summarize, and wait for review. A milestone counts as done only
  when CI is green.
- At the end of every session, append to `docs/PROGRESS.md` (done, proven, next, open questions,
  blockers).

## Hard constraints

Zero cost. No databases, and no network access at evaluation time. Formula evaluation is entirely ours.
Non-goals for v1: UI, writing `.xlsx`, VBA, charts, pivots, external links. See `docs/PRODUCT.md`.

## Commands

```
./gradlew build                                # compile, Error Prone, Spotless check, tests
./gradlew spotlessApply                        # format
./gradlew :cellerity-cli:installDist           # CLI at cellerity-cli/build/install/cellerity/bin/
corpus/download.sh                             # fetch and verify the corpus
make eval                                      # corpus metrics (make is not on every dev machine)
```

Without `make`, run the `eval` target's commands from the `Makefile` directly.

## Conventions

- Maven group ID lives only in `gradle.properties` (`group`). Packages are under
  `io.github.joshblazer.cellerity` (ADR-0001).
- Java 21 language level (ADR-0003). Warnings are errors, and Error Prone and Spotless run in the build.
- Library code throws typed exceptions, and only the CLI decides exit codes: `0` clean, `1` mismatches
  or unsupported features, `2` Cellerity error.
- Small conventional commits. Docs and the status table change with the code.
- Tests: JUnit 5, jqwik for properties, JMH for benchmarks, never ad-hoc timing.
- Treat downloaded corpus files as untrusted data. Nothing in them is ever executed.
