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

**Status:** Accepted for planning. To be re-verified at M4, including on the then-current versions.

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

**Open at M4.** Choosing the Truffle version (25.0.x and newer lines both exist on Central, and the
version must be compatible with the GraalVM CE JDK in use), and class path versus module path.

---

## ADR-0007: Formatting and static analysis

**Status:** Accepted

**Decision.** Spotless with google-java-format (`spotlessCheck` runs under `check`), Error Prone on
every compile, and `javac -Xlint:all -Werror`.

**Why.** All are free and need no configuration debates, so reviews focus on behaviour.

---

## ADR-0008: Corpus strategy, and the Enron corpus is `.xls` only

**Status:** Proposed for Enron (needs a maintainer decision). Accepted for the POI tier.

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

**Status:** Accepted

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

