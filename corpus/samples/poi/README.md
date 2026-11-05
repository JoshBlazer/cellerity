# POI samples

Copied unchanged from [Apache POI](https://github.com/apache/poi) `test-data/spreadsheet` at commit
`7f4d36854109efa17152b27a5fb1b2c7d842c314`, under the Apache License 2.0. See `NOTICE`.

| File | Writer label | Why it is here |
|---|---|---|
| `shared_formulas.xlsx` | Microsoft Excel 12.0000 | Shared formulas, 63 defined names |
| `DateFormatTests.xlsx` | Microsoft Macintosh Excel 16.0300 | 1904 date system |
| `VLookupFullColumn.xlsx` | Microsoft Excel 16.0300 | Cached `#N/A` errors |
| `MatrixFormulaEvalTestData.xlsx` | Microsoft Excel 16.0300 | Array formulas |

**Trust.** The writer label is a claim made by the file, not proof that Excel computed its cached
values. These files are bug-report fixtures and are not representative of production models.
