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

### Not yet implemented

Formula parsing and evaluation, `verify`, `coverage`, `bench`, and `api`.
