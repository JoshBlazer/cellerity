# Risks

| # | Risk | Severity | Mitigation | State |
|---|---|---|---|---|
| R1 | Excel's undocumented edge cases in coercion, precision, and dates | High | `docs/design/numerics.md` separates documented behaviour from hypotheses. Each rule is tested against cached values | Open |
| R2 | Function breadth: hundreds of functions | High | Usage-ranked implementation; coverage reported as a share of real formula calls | Open |
| R3 | Corpus licensing and redistribution | Medium | Pinned download manifest with checksums; only clearly licensed samples committed | Managed for POI tier |
| R4 | **No real-world `.xlsx` corpus yet.** Enron is entirely `.xls` (20,872 files, no `.xlsx`). POI test data is mostly bug reproductions, so it is not representative | **High** | ADR-0013: cleaned Enron read with POI HSSF inside `eval/`. Rankings from POI data must not be called real-world usage | Decided; implement before M2 |
| R5 | Corpus privacy: the original Enron data contains personal information such as Social Security numbers | Medium | Use only the cleaned Nuix subset; commit none of it; publish aggregates only | Open |
| R6 | Creating new ground-truth workbooks requires Excel | Medium | Corpus and community contributions; anything else labeled lower-trust. Synthetic samples are reader fixtures only | Open |
| R7 | **JIT only on GraalVM JDKs.** Confirmed: stock Temurin 25 uses the interpreted fallback runtime. Teams on standard JDKs do not get the headline speed, which weakens the tagline for those teams | **High** | ADR-0006: say so in every public claim; benchmark on both GraalVM CE and stock JDK; measure the best non-JIT path | Open; re-verify at M4 |
| R8 | Volatile and dynamic-reference functions (`OFFSET`, `INDIRECT`) weaken incremental recalc | Medium | Handled conservatively and documented at M3 | Open |
| R9 | Benchmarks are easy to get wrong | Medium | JMH only, with warmup and methodology documented; hardware stated | Open |
| R10 | Stale or non-Excel cached values would corrupt ground truth | Medium | Reader exposes writer and calc settings; files labeled by trust (numerics section 5) | Reader part done |
| R11 | Hostile workbooks: zip bombs, XML entity attacks, corrupt files | Medium | DTDs off, size limits, typed failures; POI fuzzer files and XML bombs are in the load corpus | Managed; see `make eval` |
| R14 | A check can pass by checking less. `PoiCrossCheck` once reported zero disagreements because it had damaged its own inputs | Medium | Coverage floors, checksum re-verification, and counted skips. Fidelity reports in M2 must use the same pattern | Managed for the cross-check; apply at M2 |
| R12 | Personal-account coordinates look less neutral to enterprise evaluators | Low | Accepted in ADR-0001; can relocate at a major version | Accepted |
| R13 | Name confusion or trademark conflict: two "cellerity" US trademark filings appeared in a search and could not be opened, and similar names exist (Celerity, Cellarity) | Medium | Check USPTO records before public launch; this is not legal clearance | **Open, check before M6** |
