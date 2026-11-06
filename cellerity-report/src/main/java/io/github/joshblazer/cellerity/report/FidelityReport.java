package io.github.joshblazer.cellerity.report;

import io.github.joshblazer.cellerity.interp.Category;
import io.github.joshblazer.cellerity.interp.Comparison;
import io.github.joshblazer.cellerity.interp.Coverage;
import io.github.joshblazer.cellerity.interp.VerifyResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A fidelity report as one static HTML page: no scripts, and nothing fetched from a network.
 * Workbook text is untrusted, so everything taken from a result is escaped.
 */
public final class FidelityReport {
  private static final int PATTERNS_SHOWN = 100;
  private static final int CELLS_SHOWN = 500;

  private FidelityReport() {}

  /**
   * Builds the page.
   *
   * @param showCells whether to list mismatched cells with their formulas and values. Keep this off
   *     for corpus runs, whose results may only be published as aggregates (ADR-0013).
   */
  public static String html(String title, VerifyResult r, Coverage coverage, boolean showCells) {
    StringBuilder out = new StringBuilder();
    out.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>")
        .append(escape(title))
        .append("</title><style>")
        .append("body{font:15px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;")
        .append("padding:0 1rem}table{border-collapse:collapse;margin:.5rem 0 1.5rem}")
        .append("td,th{border:1px solid #bbb;padding:.2rem .6rem;text-align:left}")
        .append("td.n{text-align:right}code{font-size:90%}")
        .append("</style></head><body>\n<h1>")
        .append(escape(title))
        .append("</h1>\n<p><strong>Fidelity ")
        .append(percent(r.fidelity()))
        .append("</strong> of ")
        .append(r.compared())
        .append(" compared cells. <strong>Coverage ")
        .append(percent(r.coverage()))
        .append("</strong> of ")
        .append(r.total())
        .append(" formula cells. Fidelity says nothing about cells that were not compared.</p>\n");
    out.append("<p>Numbers match within a relative tolerance of ")
        .append(Comparison.TOLERANCE)
        .append(", an absolute noise floor of ")
        .append(Comparison.NOISE_FLOOR)
        .append(", or a solver's documented accuracy. ")
        .append(r.comparedWithCachedInput())
        .append(" compared cells rest on a cached value upstream.</p>\n");

    Map<String, Long> categories = new LinkedHashMap<>();
    categories.put("Exact", r.count(Category.EXACT));
    categories.put("Display-only (equal to 15 digits)", r.count(Category.DISPLAY_ONLY));
    categories.put("Within tolerance", r.count(Category.WITHIN_TOLERANCE));
    categories.put("Real mismatch", r.count(Category.REAL_MISMATCH));
    categories.put("Unsupported function", r.count(Category.UNSUPPORTED_FUNCTION));
    categories.put("Unsupported feature", r.count(Category.UNSUPPORTED_FEATURE));
    categories.put("Not comparable", r.count(Category.NOT_COMPARABLE));
    table(out, "Formula cells", "Category", categories, Integer.MAX_VALUE);
    table(out, "Why cells were not comparable", "Reason", r.notComparable(), Integer.MAX_VALUE);
    table(out, "Unsupported features", "Feature", r.unsupportedFeatures(), Integer.MAX_VALUE);
    table(out, "Unsupported functions", "Function", r.unsupportedFunctions(), Integer.MAX_VALUE);
    table(
        out,
        "Root mismatches, reading no other wrong cell",
        "Pattern",
        r.rootPatterns(),
        PATTERNS_SHOWN);
    table(out, "All mismatches", "Pattern", r.mismatchPatterns(), PATTERNS_SHOWN);

    out.append("<h2>Function coverage</h2>\n<p>")
        .append(percent(coverage.rate()))
        .append(" of ")
        .append(coverage.totalCalls())
        .append(" function calls are to functions Cellerity implements.</p>\n");
    table(
        out, "Functions not implemented, by calls", "Function", coverage.missing(), PATTERNS_SHOWN);

    if (showCells && !r.mismatches().isEmpty()) {
      cells(out, r);
    }
    return out.append("</body></html>\n").toString();
  }

  private static void cells(StringBuilder out, VerifyResult r) {
    out.append("<h2>Mismatched cells</h2>\n<table><tr><th>Sheet</th><th>Cell</th><th>Excel</th>")
        .append("<th>Cellerity</th><th>Cause</th><th>Formula</th></tr>\n");
    r.mismatches().stream()
        .limit(CELLS_SHOWN)
        .forEach(m ->
            out.append("<tr><td>")
                .append(escape(m.sheet()))
                .append("</td><td>")
                .append(escape(m.cell()))
                .append("</td><td>")
                .append(escape(m.expected()))
                .append("</td><td>")
                .append(escape(m.actual()))
                .append("</td><td>")
                .append(m.root() ? "root" : "downstream")
                .append("</td><td><code>")
                .append(escape(m.formula()))
                .append("</code></td></tr>\n"));
    out.append("</table>\n");
    if (r.mismatches().size() > CELLS_SHOWN) {
      out.append("<p>First ").append(CELLS_SHOWN).append(" shown.</p>\n");
    }
  }

  private static void table(
      StringBuilder out, String heading, String column, Map<String, Long> rows, int limit) {
    if (rows.isEmpty()) {
      return;
    }
    out.append("<h2>")
        .append(escape(heading))
        .append("</h2>\n<table><tr><th>")
        .append(escape(column))
        .append("</th><th>Cells</th></tr>\n");
    rows.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(limit)
        .forEach(e ->
            out.append("<tr><td>")
                .append(escape(e.getKey()))
                .append("</td><td class=\"n\">")
                .append(e.getValue())
                .append("</td></tr>\n"));
    out.append("</table>\n");
  }

  private static String percent(double share) {
    return String.format(Locale.ROOT, "%.4f%%", 100 * share);
  }

  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
