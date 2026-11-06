# Numerics and fidelity rules

Status: design note, written before the evaluator. M0 implements only date-serial conversion and the
value model. Everything else here is the plan for M2, and claims about undocumented Excel behaviour
are **hypotheses to test against cached values**, marked as such.

## 1. Number representation

Excel stores numbers as IEEE 754 binary64 doubles, and Cellerity uses `double` internally.

| Topic | Excel behaviour | Confidence | Cellerity plan |
|---|---|---|---|
| Storage in `.xlsx` | Decimal text in `<v>`, typically enough digits to round-trip a double | Observed in files | Parse with `Double.parseDouble`; reject non-finite values and Java-only syntax |
| Display | At most 15 significant digits | Documented | Compare at 15 digits only for the display-only category |
| Entry | Digits past the 15th become zero when typed | Documented | Not applicable: we read stored values. `--set` takes full doubles |
| Overflow | Results beyond about ±1.797E308 give `#NUM!` | Documented limit | `#NUM!` instead of `Infinity` |
| Not-a-number | No NaN value exists in cells | Observed | Functions must map NaN to an error value (`#NUM!` or `#DIV/0!`, per function) |
| Subnormals | Limits are documented at about 2.2E-308 | Hypothesis: flushed to 0 | Test at M2; document result |
| Negative zero | Not displayed | Hypothesis | Treat `-0.0` as equal to `0.0` for comparison |

### Near-zero subtraction and comparison (hypotheses)

[Microsoft documents](https://learn.microsoft.com/en-us/office/troubleshoot/excel/floating-point-arithmetic-inaccurate-result)
that Excel shows floating-point artifacts. Community findings add behaviour that is not fully
specified:

- A final add or subtract whose result is very close to zero relative to its operands may snap to
  exactly 0. A commonly cited example is that `=1.1-1-0.1`-style expressions can differ depending on
  whether they are wrapped in parentheses.
- `=` and other comparisons may treat values as equal if they match to about 15 significant digits.

The plan for M2 was to start with plain IEEE behaviour, collect mismatches by category from corpus files,
and add a snap or comparison rule only with evidence. The corpus supported a snap to zero (ADR-0016). It did not call
for a comparison rule, so comparisons remain exact.

## 2. Dates

A date is a number. Whether a cell "is a date" depends only on its number format, so the reader
reports `dateFormatted` separately and keeps the value as a plain number.

### 1900 system (default)

- Serial 1 = 1900-01-01.
- Serial 60 = the fictitious 1900-02-29, kept deliberately for Lotus 1-2-3 compatibility.
- Serial 61 = 1900-03-01. For serials ≥ 61, date = 1899-12-30 + serial days.
- For serials 1 to 59, date = 1899-12-31 + serial days.
- Serial 0 displays as "1900-01-00". Cellerity's ISO rendering shows it as `1900-01-00`.
- Maximum serial: 2958465 = 9999-12-31.

Serial 60 cannot be a `java.time.LocalDate`, so `DateSystem.toIso` returns strings. M2 date functions
must handle it explicitly. `WEEKDAY` on serials below 60 follows Excel's serial arithmetic rather than
the real calendar, because the fictitious day shifts it by one (to verify against Microsoft's
documentation and the corpus at M2).

### 1904 system

- Enabled by `<workbookPr date1904="1"/>`.
- Serial 0 = 1904-01-01. There is no fictitious day.
- Maximum serial: 2957003 = 9999-12-31.
- Same real date in both systems: serial(1900) = serial(1904) + 1462, for dates after 1900-03-01.

### Time

The fractional part is the fraction of a day. Rendering rounds to the nearest millisecond, which can
carry into the next second or day. Excel's own display rounding depends on the format; M2 tests it.

## 3. Rounding and related functions (M2 plan)

- `ROUND` rounds half away from zero. Its operation is decimal in appearance while inputs are binary:
  `ROUND(2.675, 2)` is the classic trap. Hypothesis: Excel returns 2.68, whereas plain binary rounding
  gives 2.67. Implement through `BigDecimal` on the shortest repr, test against cached values, and
  record the result.
- `MOD` takes the sign of the divisor. `INT` floors. `TRUNC` truncates.
- Operator quirk, handled by the parser: unary minus binds tighter than `^`, so `-2^2` is `4`.

## 4. Mismatch categories

Defined now so that the harness in M2 cannot adjust them to flatter results. Checked in this order:

| Category | Definition | Counts against fidelity |
|---|---|---|
| Exact match | Same type; numbers equal (with `-0.0` = `0.0`); text equal; same error; both blank | No |
| Display-only difference | Numbers differ, but are equal when both are rounded to 15 significant digits | No |
| Within documented tolerance | Numbers differ with `abs(a-b) <= max(1e-15, 1e-12 * max(abs(a), abs(b)))`, or within a solver's documented accuracy (ADR-0015) | No |
| Unsupported function | Formula calls a function Cellerity does not implement | No, reported separately |
| Unsupported feature | Formula uses a feature out of scope for the current version | No, reported separately |
| Not comparable | The file holds no valid expectation, with a stated reason (ADR-0014) | No, reported separately |
| Real mismatch | Anything else, including every type mismatch | **Yes** |

Rules that keep this honest:

- Every report states the count of each category. Fidelity is never shown without coverage, since a
  workbook of unsupported formulas would otherwise score 100%.
- Fidelity = (matches of the first three categories) / (those plus real mismatches).
- The tolerances are provisional. Any change needs an ADR, and reports state the values in use and how
  many cells depend on tolerance rather than an exact match.

## 5. Trust in ground truth

Cached values are ground truth only if Excel actually computed them. The reader therefore exposes:

- `docProps/app.xml` `Application` and `AppVersion`, which name the writing application. Files claiming
  a writer other than Excel are labeled lower-trust. The field can be spoofed, and a library can edit
  a file while keeping the Excel label, so this is a label and not proof.
- `<calcPr>` `calcMode` and `fullCalcOnLoad`. Manual calculation mode can leave cached values stale.

The brief's categories have no slot for "cached value not trustworthy". M2 will propose one by ADR if
the corpus shows it is needed.
