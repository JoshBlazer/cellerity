# Decision records

One entry per non-obvious choice. Newest last. To change a decision, add a new ADR that supersedes
the old one rather than editing history.

Status values: **Accepted**, **Proposed** (awaiting maintainer input), **Superseded by ADR-NNNN**.

---

## ADR-0001: Repository home and Maven coordinates

**Status:** Accepted (maintainer decision, 2026-11-04)

**Context.** The name check found `cellerity` free on Maven Central, but the GitHub handle
`Cellerity` belongs to an unrelated, dormant personal account (created 2019, no public repos). That
rules out `github.com/cellerity` and the `io.github.cellerity` Central namespace, which requires
owning that account.

**Decision.**
- Repository: `github.com/JoshBlazer/cellerity`.
- Maven group ID: `io.github.joshblazer`. Artifact IDs are the module names (`cellerity-core`, ...).
- Java packages live under `io.github.joshblazer.cellerity`.
- The group ID is defined once, as the `group` property in `gradle.properties`. Build scripts derive
  everything else from it (`project.group`), including the CLI main class and
  `Automatic-Module-Name`.

**Future moves.** A move to an organisation or custom domain would happen only at a major version,
using Maven relocation: the old coordinates publish a final relocation POM pointing at the new ones.
Java packages would move in the same major version, because that is a source-breaking change.

**Consequences.** The coordinates carry a personal account name, which is less neutral for
enterprise evaluators. This was accepted knowingly over a new organisation.

**Proof.**
`./gradlew :cellerity-core:generatePomFileForMavenPublication` writes a POM with this group ID, and
`git grep -n "io.github.joshblazer" -- "*.kts" "*.toml" "*.properties"` matches only
`gradle.properties`.

---

## ADR-0002: Build tool is Gradle (Kotlin DSL) with the wrapper

**Status:** Accepted

**Context.** The brief allows Maven or Gradle. ADR-0001 requires the group ID in a single build
property.

**Decision.** Gradle 9.8 with the Kotlin DSL, a committed wrapper with a pinned distribution
checksum, a version catalog (`gradle/libs.versions.toml`), and one convention plugin in `buildSrc`.

**Why.** In a Maven reactor, each child's `<parent>` block must repeat the group ID as a literal, so
Maven 3 cannot satisfy the single-property requirement without warnings and extra plugins. In Gradle,
a `group` entry in `gradle.properties` applies to every project. Gradle also has first-class JMH and
annotation-processor support, which M4 needs for the Truffle DSL.

**Trade-offs.** Gradle builds are harder to read than POMs for some Java teams. Consumers are not
affected, because published artifacts carry ordinary POMs. `make` targets wrap the common commands.

---

## ADR-0003: Java 21 bytecode, built and tested on Java 21 and 25

**Status:** Accepted

**Context.** The brief says to target the current LTS with a minimum of Java 21. Java 25 is the
current LTS as of this ADR.

**Decision.** Compile with `--release 21` (the `javaRelease` property). CI builds and tests on both
21 and 25. Language features newer than 21 are not used.

**Why.** Banks and insurers adopt LTS releases slowly, so a Java 25 baseline would lock out much of
the target audience. Testing on 25 keeps it first-class. `--release` also guards against accidental
use of newer JDK APIs.

**Revisit when** a Java 25-only feature would materially help, or at the next major version.

---

## ADR-0004: Own streaming `.xlsx` reader using only the JDK

**Status:** Accepted

**Context.** The brief allows Apache POI or a custom reader for reading `.xlsx`.

**Decision.** `cellerity-io` is a custom reader built on `java.util.zip` and the JDK's StAX parser,
with no third-party runtime dependencies. POI appears only in `cellerity-bench` (M4) as the
comparison target.

**Why.**
- Fidelity depends on the exact stored form of formulas and cached values. Owning the reader keeps
  those untouched, with no intermediate object model.
- POI's dependency tree (XMLBeans and others) is heavy for a library meant to embed in services, and
  POI as both reader and benchmark opponent would muddy load-time comparisons.
- The needed subset of SpreadsheetML is small. See `docs/formats/xlsx-subset.md`.
- Hostile input can be handled in one place: DTDs disabled, and size limits on every part.

**Trade-offs.** We own format edge cases. This is mitigated by running over POI's own test data,
including fuzzer-found crash files (`make eval`). `.xls` is not read (see ADR-0008).

---

## ADR-0005: License is Apache-2.0

**Status:** Accepted

**Why Apache-2.0 over MIT.** Its explicit patent grant matters to the banks and insurers this project
targets, and it is the norm in the Java ecosystem. Both options in the brief are permissive, so
adoption is not otherwise affected.

---

## ADR-0006: Truffle runtime and JDK strategy

**Status:** Accepted. Re-verified at M4, with the results below.

**Context.** The brief requires recording which JDKs get optimized Truffle execution, and a correct
path on stock OpenJDK.

**Evidence.**
The [GraalVM embedding documentation](https://github.com/oracle/graal/blob/master/docs/reference-manual/embedding/embed-languages.md#runtime-optimization-support)
(read 2026-11-04) lists:

| Java runtime | Optimizing runtime | Fallback runtime |
|---|---|---|
| Oracle GraalVM 25 | Yes, with additional compiler optimizations | Yes |
| GraalVM Community Edition 25 | Yes, no extra configuration | Yes |
| Oracle JDK 25 or OpenJDK 25 | Polyglot isolate only | Yes |
| JDK 21 runtimes | Polyglot isolate only | Yes |

A probe with `truffle-api` and `truffle-runtime` 25.0.5 on Eclipse Temurin 25.0.3 confirms the
fallback, on both the class path and the module path:

```
runtime=Interpreted class=com.oracle.truffle.api.impl.DefaultTruffleRuntime
```

The `truffle-api` 25.0.5 classes are major version 61 (Java 17), so they load on our Java 21 baseline.

**Decision.**
1. **Optimized path:** GraalVM Community Edition JDK. It is free, and benchmarks run and publish on
   it. Oracle GraalVM is not a project dependency, and Cellerity never uses non-community artifacts.
2. **Correct path on stock OpenJDK and Java 21:** runs without JIT, either on the Truffle fallback
   runtime or on the reference interpreter. M4 picks the default by measurement and records it here.
3. Correctness is identical on all paths, because differential tests run on stock JDKs as well as on
   GraalVM CE.
4. Polyglot isolates are out of scope for v1. They would require shipping a native-image guest build.

**Consequences.** The tagline's "JIT-compiled" is true only on a GraalVM JDK. All public claims must
say so, and benchmark pages must name the JDK. See `docs/RISKS.md`.

**Settled at M4 (measured 2026-11-06, `TruffleProbe` in the `bench` workflow).**
- Truffle 25.0.5 with GraalVM CE 25.0.2 reports the runtime `GraalVM CE`, and formulas compile at Tier 1 and Tier 2.
- This works on the class path and on the module path, so the class path stays, for simplicity.
- `new RootNode(null)` compiles, so no `TruffleLanguage` or polyglot `Context` is needed.
- On Temurin the runtime is `Interpreted`.
- **Default on stock JDKs (item 2):** the reference interpreter. It is also the default on GraalVM CE for now, since Truffle
  showed no speed benefit there (`docs/benchmarks/README.md`). Truffle is chosen with `--engine truffle`.

---

## ADR-0007: Formatting and static analysis

**Status:** Accepted

**Decision.** Spotless with google-java-format (`spotlessCheck` runs under `check`), Error Prone on
every compile, and `javac -Xlint:all -Werror`.

**Why.** All are free and need no configuration debates, so reviews focus on behaviour.

---

## ADR-0008: Corpus strategy, and the Enron corpus is `.xls` only

**Status:** Accepted for the POI tier. The Enron options below were resolved by ADR-0013.

**Context.** The brief suggests the Enron spreadsheet corpus. Measured on 2026-11-04:
- The Enron mail predates `.xlsx` (introduced with Excel 2007). The
  [`SheetJS/enron_xls`](https://github.com/SheetJS/enron_xls) mirror (CC0) holds 20,872 `.xls` files
  and no `.xlsx`.
- Its figshare record (CC BY 4.0) currently lists only a PDF.
- The mirror's README says the original data contained personal information such as Social Security
  numbers, and that only the separate Nuix subset was cleaned.

So Enron cannot exercise an `.xlsx`-only reader, and the brief's reader scope is `.xlsx`.

**Decision (accepted).** Tier 1 is `.xlsx` files from Apache POI's `test-data/spreadsheet` at a pinned
commit, referenced by checksum in `corpus/manifest.tsv`. It is good for reader robustness because it
includes fuzzer crash files and XML bombs. It is **not** representative of real-world models, so its
function rankings must not be presented as real-world usage. Files are downloaded rather than
committed, apart from a few small samples.

**Options for real-world corpus coverage (undecided).**
1. Add a BIFF (`.xls`) reader for corpus use. Cached values stay Excel's own, so this keeps ground
   truth, but it means more reader work, or using POI HSSF for evaluation only.
2. Convert Enron to `.xlsx` with LibreOffice. This is cheap, but cached values pass through
   LibreOffice, so results must be labeled lower-trust.
3. Find a public corpus of real `.xlsx` models, with licenses checked.

**Recommendation.** Option 1 with POI HSSF confined to `eval/`, because it preserves Excel's cached
values at low cost. It would extend constraint 4's wording to `.xls`, so it needs maintainer approval
before M2. Whatever is chosen, use only the cleaned Nuix subset and commit none of it.

---

## ADR-0009: CLI argument parsing without a library, plus an `inspect` command

**Status:** Accepted

**Decision.** Parse CLI arguments by hand for now, because the surface is small and each runtime
dependency needs a justification. Add `cellerity inspect`, which is not in the brief's command list,
because M0 must list sheets, cells, formulas, and defined names and no other command fits.

**Revisit when** the CLI grows enough for a parser library such as picocli to pay for itself.

---

## ADR-0010: M0 `eval` prints cached values only

**Status:** Superseded in M2. `eval` now calculates. The principle stands: it refuses to print a result that a
`--set` could have made stale.

**Decision.** Until M2 there is no evaluator. `cellerity eval --get` prints the stored cached value,
as the M0 exit check specifies. `--set` exits `1` with a message, because it would require
recalculation. Printing a stale value after a `--set` would be silently wrong.

---

## ADR-0011: Apache POI as a read-only cross-check in `eval/`

**Status:** Accepted

**Context.** "Loads without crashing" says nothing about whether cells were decoded correctly, and M0
has no evaluator to expose decoding errors. Constraint 4 reserves formula evaluation for Cellerity but
allows POI for reading.

**Decision.** `eval/` depends on `poi-ooxml` for `PoiCrossCheck`, which compares stored values and
formula text in both directions. POI's formula evaluator is never called. No published module depends
on POI, so library users do not inherit it. `cellerity-eval` is a tool and not part of the library API.

**What agreement means.** Our reader decodes files the same way as another mature reader. It says
nothing about Excel fidelity. Files POI cannot read, such as Strict OOXML, are not cross-checked.

**Safeguards**, each added because of a real failure during M0:
- POI is opened with `PackageAccess.READ` and reverted. `new XSSFWorkbook(File)` saves over the file on
  close, and it damaged 320 corpus files until `corpus/download.sh` caught them by checksum.
- `make crosscheck` re-verifies every corpus checksum afterwards.
- It fails if fewer than 300 files are compared, because that damage first appeared as a false pass:
  zero disagreements across a smaller set of files.
- Known differences are detected and counted rather than filtered out: array-range members, which POI
  presents as formula cells, and `<c t="n"><v></v></c>`, which POI reads as 0 and we read as blank.

---

## ADR-0012: Rejected files stay rejected unless their data is sound

**Status:** Accepted

**Decision.** The reader is strict about content that could change a value, because a guess there would
silently corrupt results: a bad cell reference, an out-of-range shared string, or an unparseable number
rejects the workbook. It is lenient only where nothing is lost: unreadable styling means "not a date",
and a sheet entry with an empty `r:id` is listed with no cells.

**Example.** POI's `invalid_cell_reference.xlsx` has a cell at `r="B"`, with no row. POI accepts it. We
reject it, even though row 1 is the likely meaning.

**Revisit when** real-world corpus files are rejected for reasons where the intended value is clear.

---

## ADR-0013: Real-world corpus is cleaned Enron, read with POI HSSF inside `eval/` only

**Status:** Accepted (maintainer decision, 2026-11-05). Implemented in M1.

**Context.** ADR-0008 found that Enron is entirely `.xls`, and listed three options.

**Decision.** Read `.xls` corpus files with Apache POI's HSSF reader, confined to `eval/`.

- This knowingly extends constraint 4, which allowed POI for reading `.xlsx`, to `.xls` for corpus use.
- Formula evaluation stays entirely Cellerity's. POI's evaluator is never called.
- `cellerity-io` and the published library stay `.xlsx` only.
- Use only the cleaned Nuix subset of `SheetJS/enron_xls` (CC0), because the original data contains
  personal information. Commit none of it, pin it by commit and checksum, and publish aggregates only.

**Why.** It keeps Excel's own cached values as ground truth, which conversion through LibreOffice would
not.

**Open points for implementation.**
- HSSF returns formulas as POI renders them from binary tokens, not as Excel stored text. Parse rates
  on this corpus therefore partly measure POI's rendering, and reports must say so.
- The mirror says some files are TSV, SYLK, HTML, or BIFF2 despite the extension. These will be counted
  and reported as unreadable rather than ignored.
- Measured in M1: 616 files, of which 452 load (404 say Excel, 48 state no writer) and 164 do not (141 are
  not OLE2, 23 predate BIFF8). The 2.0 million cells include 831,499 formula cells.
- POI's usermodel throws on data-table cells, so `eval/` reads them from raw `TABLE` records.
- File names in the manifest include mailbox names, as they do on the public mirror. They are needed to
  fetch each file. Committed results drop file and sheet names.
- Fidelity on this corpus can differ from `.xlsx`-native behaviour, so results are reported per corpus.

---

## ADR-0014: Cells with nothing to compare are "not comparable"

**Status:** Accepted (M2)

**Context.** The brief lists six mismatch categories. Some cells fit none, because the file holds no valid
expectation. Counting them as real mismatches would blame the evaluator, and counting them as matches would flatter it.

**Decision.** Add a seventh category, not comparable, with a stated reason per cell. These cells leave the fidelity
denominator, count against coverage, and are reported with their reasons:

- **Volatile:** a saved `NOW()` or `RAND()` cannot be reproduced.
- **No cached value.**
- **Add-in not loaded when saved:** the cell stored `#NAME?` and calls an Analysis ToolPak function. Before Excel 2007
  these needed an add-in, and a file saved without it stores `#NAME?` in place of an answer. A `#NAME?` on any other
  function is still a real mismatch.
- **Reads a cell whose add-in was not loaded:** the cell stored `#NAME?` and directly reads such a cell.

**Measured.** 2,008 of 706,765 Excel-labeled Enron cells have an add-in reason, and 576 are volatile.

---

## ADR-0015: Solvers compare at Microsoft's documented accuracy, with a noise floor near zero

**Status:** Accepted (M2). Changes the provisional tolerance in `docs/design/numerics.md`.

**Context.** On Enron, 8,000 `XIRR` cells differed from Excel with every gap below 1e-8 in absolute terms. Newton's
method converges quickly, so ours is tighter than Excel's. Microsoft documents `XIRR` as accurate to 0.000001 percent,
and `IRR` and `RATE` to 0.00001 percent. Separately, `COS(RADIANS(90))` gave 6.1232e-17 against Excel's 6.1257e-17.

**Decision.**
- When a formula's outermost call is `XIRR`, numbers within 1e-8 absolute count as within tolerance. `IRR` and `RATE`
  use 1e-7. This does not apply to a solver nested in a larger formula.
- Everywhere, numbers within 1e-15 absolute count as within tolerance, since they are noise around zero.
- The relative tolerance of 1e-12 is unchanged. Reports state all of these.

**Why.** The solver figures come from Microsoft's documentation and were not tuned to pass: the observed gaps happen to
fit inside them. Emulating Excel's stopping rule is not possible, since it is undocumented.

**Cost.** 8,000 cells move from real mismatch to within tolerance, and walkthroughs must say so. A wrong solver answer
closer than this would be missed.

---

## ADR-0016: A final add or subtract snaps to zero below 2^-50 of its larger operand

**Status:** Accepted (M2), as an estimate from evidence. Implements the hypothesis in `docs/design/numerics.md`.

**Context.** Microsoft documents floating-point artifacts, and it is widely reported that `=0.5-0.4-0.1` shows 0 while
`=(0.5-0.4-0.1)` does not. The rule itself is not documented. On Enron, plain IEEE arithmetic gave 28 "one is zero"
mismatches on root additions and subtractions.

**Decision.** When the outermost operation of a formula is `+` or `-`, a result smaller than 2^-50 of the larger operand
becomes 0. Both engines must apply it only at the root. It lives in `Operators.finalAddOrSubtract`.

**Evidence.** LibreOffice's precedent of 2^-48 reduced those mismatches but added 7 in the lower-trust group, where
Excel kept a tiny result that ours zeroed. 2^-50 removed those and leaves 1 root with "expected zero". No Excel was
available to test directly, so this threshold fits this corpus and could be wrong elsewhere.

---

## ADR-0017: Incremental recalculation is sound first, and the graph never calculates

**Status:** Accepted (M3). Design: `docs/design/graph.md`.

**Decision.**
- Precedents come from the AST. Where reads depend on values, the formula is always dirty, with what reads it:
  `OFFSET`, `INDIRECT`, volatile functions, range operators, and `SUMIF` or `AVERAGEIF` that read beyond their third argument.
- Both branches of a lazy function count as precedents. That is imprecise and sound.
- `cellerity-interp` depends on `cellerity-graph`, and `IncrementalWorkbook` joins the graph to the evaluator, so the
  interpreter and Truffle engines share one implementation.
- `make recalc` compares with a full recalculation on every corpus workbook, and fails on any disagreement.

**Why.** A wrong "not affected" is a silent wrong answer, and recalculating too much is only slow.

---

## ADR-0018: Truffle engine with hand-written nodes, shared by formula shape

**Status:** Accepted (M4). Design: `docs/design/truffle.md`.

**New runtime dependencies.** `org.graalvm.truffle:truffle-api` and `truffle-runtime` 25.0.5, used by `cellerity-truffle` and `cellerity-cli`, with
what they bring in. All of these are under the Universal Permissive License 1.0, compatible with Apache-2.0, per their POMs on Maven Central. They are
free, and require no Oracle GraalVM.

**Benchmark-only dependencies.** JMH 1.37, as the brief requires, and POI's evaluator in `cellerity-bench`, purely as the thing compared with. That module
is never published. Constraint 4 still holds for all published code: formula evaluation is entirely Cellerity's. The module also compiles without `-Werror`,
because JMH's generated code does not meet those rules.

**Decisions.**
- **Hand-written specialization, not the Truffle DSL.** There is one real specialization, on two numbers. A processor would add build risk with `-Werror` and
  Error Prone for little gain. Revisit if nodes grow.
- **A subtree with no node goes to the interpreter.** That keeps semantics in one place. 0.19% of corpus nodes do this.
- **Formulas of one R1C1 shape share a call target.** Per-cell targets would rarely get hot.
- **`cellerity-truffle` depends on `cellerity-interp`,** for `FormulaEngine` and delegation.

**Measured consequence.** The JIT gave no speed benefit on the one benchmarked model. This is reported as it stands, in the README and benchmark page.

