# SpreadsheetML subset read by `cellerity-io`

Element and attribute names are from ECMA-376 / ISO 29500. Both Transitional and Strict namespaces are
accepted. Anything not listed is ignored.

| Part | Read | Used for |
|---|---|---|
| `_rels/.rels` | `Relationship` with type ending `/officeDocument` | Locate the workbook part |
| `xl/workbook.xml` | `workbookPr@date1904` | Date system |
| | `sheet@name, @state, @r:id` | Sheets |
| | `definedName@name, @localSheetId, @hidden`, text | Defined names |
| | `calcPr@calcMode, @fullCalcOnLoad` | Trust in cached values |
| `xl/_rels/workbook.xml.rels` | `Relationship@Id, @Type, @Target` | Sheet parts, kinds, shared strings, styles |
| `xl/sharedStrings.xml` | `si/t`, `si/r/t` (not `rPh`) | String table |
| `xl/styles.xml` | `numFmts/numFmt@numFmtId, @formatCode`; `cellXfs/xf@numFmtId` | Date detection |
| Sheet parts | `sheetData/row@r`, `c@r, @s, @t`, `f`, `v`, `is` | Cells |
| `docProps/app.xml` | `Application`, `AppVersion` | Writer label |

## Cell types (`c@t`)

| `t` | `<v>` holds | Value |
|---|---|---|
| absent or `n` | Decimal number | Number |
| `s` | Shared string index | Text |
| `str` | Formula string result | Text |
| `inlineStr` | Text in `<is>`, no `<v>` | Text |
| `b` | `0` or `1` | Boolean |
| `e` | Error code such as `#N/A` | Error |
| `d` | ISO 8601 date | Number (serial), `dateFormatted = true` |

## Formula attributes (`f`)

| `t` | Meaning | Stored |
|---|---|---|
| absent or `normal` | Ordinary formula | `text` |
| `shared` | `si` group; `ref` and text on the master only | `sharedIndex`, plus `text` and `ref` on the master |
| `array` | Array formula over `ref` | `text`, `ref` |
| `dataTable` | What-if table | `ref`, `text` usually absent |

## Corpus manifest (`corpus/manifest.tsv`)

Tab-separated with a header row: `sha256`, `bytes`, `license`, `source_url`, `path`. `path` is relative
to `corpus/downloads/`. `corpus/download.sh` deletes any file whose checksum does not match.

## Corpus-load results (`eval/results/*.json`)

Written by `CorpusLoad`. Keys: `schema`, `date`, `corpusFiles`, `loaded`, `rejected` (count per
`XlsxReadException.Kind`), `crashed`, `sheets`, `cells`, `formulaCells`, `formulaCellsWithCachedValue`,
`definedNames`, `writers` (files per `Application` value), and `failures` (file, outcome, message).
