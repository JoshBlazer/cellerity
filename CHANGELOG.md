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

### Not yet implemented

Formula evaluation, `verify`, `coverage`, `bench`, and `api`.
