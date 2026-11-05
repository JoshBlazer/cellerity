# Design note: `cellerity-io` reader (M0)

Written before the implementation. The failing tests were committed with the stub signatures.

## Goals

- Load `.xlsx` and `.xlsm` into an immutable in-memory `Workbook`, preserving stored formula text and
  cached values exactly.
- Never crash on hostile or corrupt input: every failure is an `XlsxReadException` with a `Kind`.
- No third-party dependencies (ADR-0004).

## Public API

```java
package io.github.joshblazer.cellerity.io;

public final class XlsxReader {
  public static Workbook read(Path path) throws XlsxReadException;
  public static Workbook read(Path path, ReadLimits limits) throws XlsxReadException;
}

public record Workbook(
    List<Sheet> sheets, List<DefinedName> definedNames, DateSystem dateSystem, WorkbookInfo info) {
  public Optional<Sheet> sheet(String name);          // case-insensitive, like Excel
  public Optional<DefinedName> definedName(String name, OptionalInt localSheetIndex);
}

public record Sheet(String name, SheetKind kind, SheetState state, Map<CellRef, Cell> cells) {
  public Optional<Cell> cell(CellRef ref);            // absent means blank
}

public record Cell(
    CellRef ref, Optional<Value> value, Optional<Formula> formula, boolean dateFormatted) {}

public record Formula(Kind kind, Optional<String> text, OptionalInt sharedIndex, Optional<String> ref) {
  public enum Kind { NORMAL, SHARED, ARRAY, DATA_TABLE }
}

public record DefinedName(String name, String refersTo, OptionalInt localSheetIndex, boolean hidden) {}

public record WorkbookInfo(
    Optional<String> application, Optional<String> appVersion,
    Optional<String> calcMode, boolean fullCalcOnLoad) {}

public record ReadLimits(long maxPartBytes, long maxTotalBytes, long maxCells) {
  public static final ReadLimits DEFAULT;
}

public final class XlsxReadException extends Exception {
  public enum Kind { UNSUPPORTED_FORMAT, MALFORMED, LIMIT_EXCEEDED, IO }
  public Kind kind();
}
```

`Value`, `ExcelError`, `CellRef`, and `DateSystem` live in `cellerity-core`, since the evaluator also
needs them.

## Read sequence

1. Sniff the first bytes. OLE2 (`D0 CF 11 E0`, legacy `.xls` or an encrypted `.xlsx`) gives
   `UNSUPPORTED_FORMAT`. Anything else that is not a zip gives `UNSUPPORTED_FORMAT` too.
2. Index zip entries by lower-cased name, since OPC part names are case-insensitive.
3. Find the workbook part through `_rels/.rels` (`officeDocument` relationship). If the relationships
   are missing, fall back to `xl/workbook.xml`. A `.bin` workbook part is `.xlsb`, so
   `UNSUPPORTED_FORMAT`.
4. Parse `workbook.xml`: `<workbookPr date1904>`, `<sheets>`, `<definedNames>`, `<calcPr>`.
5. Parse its relationships to map `r:id` to parts. Targets may be relative or absolute.
6. Parse `sharedStrings.xml` and `styles.xml` if present. Both are optional.
7. Parse each sheet part, and record `docProps/app.xml` if present.

## Decisions worth checking

- **Namespaces.** Elements are matched by local name, so Transitional and Strict OOXML both work.
  Worksheet parsing only descends into `sheetData`, `row`, `c`, `f`, `v`, `is`, `t`, and `r`. All other
  elements are skipped, so nested extension content cannot be mistaken for cells.
- **Rich text.** Concatenate `t` under `si` and `r`. `rPh` (phonetic hints) is excluded.
- **`ST_Xstring`.** `_xHHHH_` escapes are decoded in strings and formula text.
- **Shared formulas.** M0 stores the master's text and `si`, and followers keep only `si`. Translating
  them requires reference shifting, which belongs to M1 with the parser.
- **Blank cells.** A `<c>` with no value and no formula holds only styling and is not stored. Absence
  means blank.
- **Formula with no `<v>`.** `value` is empty, which is different from a cached empty string.
- **Missing `r` attributes.** Both `row@r` and `c@r` are optional in the schema, so positions continue
  from the previous row or cell.
- **Numbers.** A numeric `<v>` must be plain decimal syntax and finite. `parseDouble` alone would also
  accept `NaN`, `0x1p3`, and `1d`.

## Security

- StAX: `newDefaultFactory()`, so nothing on the class path can replace the parser. DTDs and external
  entities are off. An entity reference therefore fails the parse, which stops XXE and billion-laughs
  attacks.
- Every part is read through a counting stream that enforces `maxPartBytes` and `maxTotalBytes`, which
  limits zip bombs by actual decompressed bytes rather than by the sizes the zip claims. `maxCells`
  limits memory use.
- Part names come from the file. They are only used as lookup keys in the zip index and never touch
  the file system, so path traversal does not apply.

## Honest limits at M0

- The model is fully in memory: streaming applies to parsing and not to the result, and very large
  workbooks are limited by heap and `maxCells`.
- There is no shared-formula expansion, number-format rendering, or `.xls` support.
- Rejection policy is in ADR-0012. For example, a cell with `r="B"` rejects the whole workbook.
- Blank styled cells are dropped, so empty formatting is not reported.
