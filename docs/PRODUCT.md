# Product

**Tagline:** Run your Excel model as a JIT-compiled Java service, and prove it matches Excel.

*JIT compilation requires a GraalVM JDK. Stock OpenJDK runs correctly but without JIT. See ADR-0006.*

## Problem

Banks, insurers, and pricing teams run critical logic in Excel, and engineers constantly need those
models inside Java systems. Apache POI evaluates formulas but is slow and incomplete. Fast engines are
commercial and closed.

## What Cellerity is

An open-source engine that compiles a workbook's formulas into a GraalVM Truffle language runtime, so
models become fast, typed Java services. Excel fidelity is **measured and published**, not assumed.

## Priorities

1. Demonstrate excellent engineering.
2. Be pitchable and useful to teams that run Excel models in production.

## Users and surfaces

| User | Need | Surface |
|---|---|---|
| Backend engineer | Run a workbook model inside a Java service | Library + generated typed API |
| Quant / actuary / analyst | Keep authoring in Excel, trust production results | Fidelity report |
| Tech lead | Is Cellerity safe for this workbook? | `cellerity verify` + report |
| Evaluator / reviewer | How good is it, really? | Public fidelity and benchmark pages |

## Target library API (sketch; refined by ADR at M5)

```java
var model = Cellerity.load(Path.of("pricing.xlsx"));
model.set("Inputs!Age", 34);
model.set("CoverAmount", 50_000);      // defined names supported
double premium = model.getNumber("Outputs!Premium");

// Generated typed API from defined names:
PricingModel pm = PricingModel.load(Path.of("pricing.xlsx"));
Quote q = pm.quote(new QuoteInput(34, 50_000));
```

## CLI

```
cellerity eval     model.xlsx [--set Name=Value ...] [--get Name ...]
cellerity inspect  model.xlsx           # list sheets, names, cells, formulas (ADR-0009)
cellerity verify   model.xlsx           # recompute every formula, compare to Excel's cached values
cellerity coverage model.xlsx           # which functions/features are used and supported
cellerity bench    model.xlsx           # JMH-backed recalculation benchmark vs Apache POI
cellerity api      model.xlsx --package com.acme.pricing   # generate a typed Java API
```

Exit codes: `0` clean, `1` mismatches or unsupported features found, `2` Cellerity error.

The README status table shows which of these work today.

## Reports (static HTML, offline, no CDN)

- **Verify report:** per sheet and cell, mismatches with expected versus actual, mismatch category, and
  the formula; overall fidelity percentage.
- **Public fidelity page** (GitHub Pages): results across the test corpus, function coverage ranked by
  usage, and known gaps.
- **Benchmark page:** methodology, hardware, and results versus Apache POI.

## Non-goals (v1)

No spreadsheet UI, no writing back to `.xlsx` (except optional value export), no VBA or macros, no
charts or pivot tables, no external workbook links.

## Hard constraints

1. Zero cost: no paid libraries, APIs, or hosting. GitHub Actions, GitHub Pages, and Maven Central only.
2. Java 21 minimum (ADR-0003); Gradle (ADR-0002).
3. GraalVM Community Edition and Truffle from Maven Central, with a correct path on stock OpenJDK
   (ADR-0006).
4. Formula evaluation is entirely Cellerity's own, and files are read by Cellerity's own reader
   (ADR-0004).
5. Fidelity ground truth is Excel's cached values in real workbooks. LibreOffice results are lower-trust
   and labeled as such.
6. No databases, and no network access at evaluation time.
7. Apache-2.0 (ADR-0005).
