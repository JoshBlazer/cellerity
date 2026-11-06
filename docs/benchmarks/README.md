# Benchmarks

**JIT compilation gave no measured speed benefit on this benchmark.** On GraalVM CE, formulas do compile, but the Truffle
engine was no faster than the plain interpreter. Cellerity's lead over Apache POI here comes from its evaluator design, not
from the JIT. Read the limits below before quoting any number.

Measured on 2026-11-06 by `bench` run `42251602885`. Raw JMH output is in this directory.

## Results

Average time per operation, in **milliseconds**, lower is better. The ± is JMH's 99.9% confidence interval from 10
measurements across 2 forks.

**Full recalculation:** change an input, and calculate all 10,003 formulas from scratch.

| Engine | GraalVM CE 25.0.2 | Temurin 25.0.5 |
|---|---|---|
| Cellerity interpreter | 14.17 ± 1.86 | 16.20 ± 0.95 |
| Cellerity Truffle | 16.19 ± 0.86 | 15.63 ± 1.62 |
| Apache POI 6.0.0 | 67.57 ± 2.42 | 62.27 ± 1.96 |

**One input changed:** change an input, and bring every formula up to date as cheaply as each engine can.

| Engine | GraalVM CE 25.0.2 | Temurin 25.0.5 |
|---|---|---|
| Cellerity interpreter | 0.611 ± 0.016 | 0.620 ± 0.012 |
| Cellerity Truffle | 0.589 ± 0.033 | 0.621 ± 0.013 |
| Apache POI 6.0.0 | 16.36 ± 9.24 | 16.12 ± 6.48 |

## What this shows

- **Against POI on this model:** about 4 times faster for a full recalculation, and about 26 times for one changed input. POI's
  figure for the second is noisy, with an error of 40% to 56%, so treat that ratio as rough.
- **Truffle against the interpreter:** no clear difference on either JDK. On GraalVM CE, Truffle was slower on full
  recalculation (their intervals touch) and about level on one changed input.
- **Truffle on stock OpenJDK costs little here,** even without a JIT. That is the fallback runtime working as ADR-0006 expects.

## Why the JIT does not help here (not yet measured)

These are hypotheses. No profile has been taken.

- Each cell read crosses a `@TruffleBoundary` into a hash-map lookup in the evaluator, and each function call crosses another
  into the shared library. Compiled code can only optimize what lies between those.
- Every value is a boxed `Value`, and each is returned across boundaries, so few can be removed.
- This model's time may sit mostly in `ROUND`, `VLOOKUP`, and `SUM`, which are the same Java in both engines.

Making the JIT pay would likely mean compiling across cell reads and unboxing numbers, and it may need a different model to
show. This is open work. Until a benchmark shows a benefit, the CLI's default stays the interpreter.

## Methodology

- **Tool:** JMH 1.37, `Mode.AverageTime`, with `-f 2 -wi 8 -w 5s -i 5 -r 5s -jvmArgsAppend -Xmx4g`. So 2 forks, each with 8 warmup
  iterations of 5 seconds and 5 measured iterations of 5 seconds.
- **Model:** synthetic, from `ModelBook`, with 2,000 rows. 200 numeric inputs and a lookup table, 5 repeating formula columns per
  row (arithmetic, `IF`, exact `VLOOKUP`, `ROUND`, `SUM`), and 3 summaries, so 10,003 formulas.
- **Each operation** changes one of the 200 inputs, in rotation, to a new value.
- **Correctness check:** each trial's setup compares the engine's checksum with POI's, within 1e-6, and fails on disagreement. No
  engine can be fast by being wrong on this model.
- **Full recalculation:** Cellerity clears its results and evaluates every formula. POI calls `clearAllCachedResultValues` and
  evaluates every formula.
- **One input changed:** Cellerity uses `IncrementalWorkbook.recalculate`, which updates all affected formulas, and reads the total.
  POI calls `notifyUpdateCell` and evaluates every formula, since it recalculates lazily. So POI's figure includes asking for
  10,003 mostly unchanged cells, while Cellerity's includes working out what is affected.
- **Single-threaded,** in one JVM per fork.

## Environment

GitHub-hosted `ubuntu-latest`, a **shared virtual machine**, with Ubuntu 26.04.1, an AMD EPYC 9V45 (4 vCPUs, 2 cores with 2 threads each,
Microsoft hypervisor), and 15 GB of memory. `2026-11-06-environment.txt` has the full record.

Compiled with Temurin 25 at `--release 21`. Truffle 25.0.5 on the class path.

## Limits

- **Noisy hardware.** A shared runner has neighbours that JMH cannot control. Small differences mean nothing here, and that
  includes the Truffle and interpreter comparison. These are not results from a quiet dedicated machine.
- **One synthetic model.** A different mix of functions, more chains, or fewer lookups could change every ratio, including whether
  the JIT helps. This does not predict your workbook.
- **Different JDK patch levels.** GraalVM CE is 25.0.2 and Temurin is 25.0.5, so compare engines within a column, not between them.
- **Not a like-for-like product comparison.** POI implements features Cellerity does not, and fidelity differs. Neither is shown here.
- **Reading files is not included.**
- **Peak performance only.** Warmup is excluded, and warmup is where a JIT costs most. A short-lived process would see that cost.

## Reproduce

```
make bench BENCH_JAVA="$JAVA_HOME/bin/java" BENCH_OUT=results.json
```

CI: run the `bench` workflow by hand, as it is too slow and too noisy to gate on. On a quiet machine you may get better numbers, and
those would be worth publishing in place of these.
