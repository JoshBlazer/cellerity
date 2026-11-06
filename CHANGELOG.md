# Changelog

Format based on [Keep a Changelog](https://keepachangelog.com/). No version has been released.

## Unreleased

### Added

- Project scaffold: Gradle build, formatting, static analysis, CI workflows, and documentation.
- `.xlsx` and `.xlsm` reader: sheets, cells, formulas, cached values, defined names, date system, and
  writer and calculation settings. Hostile input fails with typed errors.
- CLI: `cellerity eval --get`, which prints stored values, and `cellerity inspect`.
- Corpus tooling: a checksummed manifest of 353 Apache POI test files, `make eval`, and a read-only POI
  cross-check, `make crosscheck`.

- Formula parser with error recovery and precise positions, printer, reference shifting, and shared-formula
  expansion.
- Real-world corpus tier from cleaned Enron files, parse-rate reporting, and CI regression gates.

- Reference interpreter and a shared function library, with array formulas, implicit intersection, cycle detection,
  and Excel's lazy functions.
- `cellerity verify` with static HTML reports, `cellerity coverage`, and `cellerity eval --set`. `eval` now calculates.
- `make fidelity`, with aggregate-only corpus results and CI regression gates.

- Dependency graph and incremental recalculation, with cycle detection, reported by `cellerity verify`, and `make recalc`.

### Fixed

- Every cell in a cycle is now marked as a cycle, wherever evaluation starts.

### Not yet implemented

`bench` and `api`.
