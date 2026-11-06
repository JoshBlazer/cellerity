package io.github.joshblazer.cellerity.interp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The outcome of checking formula cells against cached values. Results can be merged. */
public final class VerifyResult {
  /**
   * One real mismatch.
   *
   * @param function the outermost function, or empty
   * @param pattern a description safe to publish, holding nothing from the workbook
   * @param root true if the formula directly reads no other mismatched cell. Reads through defined
   *     names are not followed, so this can overcount roots.
   */
  public record Mismatch(
      String sheet,
      String cell,
      String formula,
      String expected,
      String actual,
      String function,
      String pattern,
      boolean root) {}

  private final Map<Category, Long> counts = new EnumMap<>(Category.class);
  private final Map<String, Long> unsupportedFunctions = new TreeMap<>();
  private final Map<String, Long> unsupportedFeatures = new TreeMap<>();
  private final Map<String, Long> mismatchFunctions = new TreeMap<>();
  private final Map<String, Long> notComparable = new TreeMap<>();
  private final Map<String, Long> mismatchPatterns = new TreeMap<>();
  private final Map<String, Long> rootPatterns = new TreeMap<>();
  private final List<Mismatch> mismatches = new ArrayList<>();
  private final int mismatchLimit;
  private long usedCachedInput;
  private long comparedWithCachedInput;

  public VerifyResult(int mismatchLimit) {
    this.mismatchLimit = mismatchLimit;
    for (Category category : Category.values()) {
      counts.put(category, 0L);
    }
  }

  void add(Category category) {
    counts.merge(category, 1L, Long::sum);
  }

  void unsupportedFunction(String name) {
    unsupportedFunctions.merge(name, 1L, Long::sum);
  }

  void unsupportedFeature(String name) {
    unsupportedFeatures.merge(name, 1L, Long::sum);
  }

  void notComparable(String reason) {
    add(Category.NOT_COMPARABLE);
    notComparable.merge(reason, 1L, Long::sum);
  }

  void usedCachedInput(boolean compared) {
    usedCachedInput++;
    if (compared) {
      comparedWithCachedInput++;
    }
  }

  void mismatch(Mismatch mismatch) {
    mismatchFunctions.merge(
        mismatch.function().isEmpty() ? "(operators only)" : mismatch.function(), 1L, Long::sum);
    mismatchPatterns.merge(mismatch.pattern(), 1L, Long::sum);
    if (mismatch.root()) {
      rootPatterns.merge(mismatch.pattern(), 1L, Long::sum);
    }
    if (mismatches.size() < mismatchLimit) {
      mismatches.add(mismatch);
    }
  }

  /** Adds another result to this one. */
  public void merge(VerifyResult other) {
    other.counts.forEach((k, v) -> counts.merge(k, v, Long::sum));
    other.unsupportedFunctions.forEach((k, v) -> unsupportedFunctions.merge(k, v, Long::sum));
    other.unsupportedFeatures.forEach((k, v) -> unsupportedFeatures.merge(k, v, Long::sum));
    other.mismatchFunctions.forEach((k, v) -> mismatchFunctions.merge(k, v, Long::sum));
    other.notComparable.forEach((k, v) -> notComparable.merge(k, v, Long::sum));
    other.mismatchPatterns.forEach((k, v) -> mismatchPatterns.merge(k, v, Long::sum));
    other.rootPatterns.forEach((k, v) -> rootPatterns.merge(k, v, Long::sum));
    usedCachedInput += other.usedCachedInput;
    comparedWithCachedInput += other.comparedWithCachedInput;
    for (Mismatch m : other.mismatches) {
      if (mismatches.size() < mismatchLimit) {
        mismatches.add(m);
      }
    }
  }

  public long count(Category category) {
    return counts.get(category);
  }

  /** Cells that matched, by any of the three matching categories. */
  public long matched() {
    return count(Category.EXACT) + count(Category.DISPLAY_ONLY) + count(Category.WITHIN_TOLERANCE);
  }

  public long compared() {
    return matched() + count(Category.REAL_MISMATCH);
  }

  public long total() {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }

  /** Matched cells over compared cells. Never quote it without {@link #coverage}. */
  public double fidelity() {
    return compared() == 0 ? 0 : (double) matched() / compared();
  }

  /** Compared cells over all formula cells. */
  public double coverage() {
    return total() == 0 ? 0 : (double) compared() / total();
  }

  public long usedCachedInput() {
    return usedCachedInput;
  }

  /** Compared cells whose result rests on a cached value upstream. */
  public long comparedWithCachedInput() {
    return comparedWithCachedInput;
  }

  public Map<String, Long> unsupportedFunctions() {
    return unsupportedFunctions;
  }

  public Map<String, Long> unsupportedFeatures() {
    return unsupportedFeatures;
  }

  public Map<String, Long> mismatchFunctions() {
    return mismatchFunctions;
  }

  /** Why cells could not be compared (ADR-0014). */
  public Map<String, Long> notComparable() {
    return notComparable;
  }

  /** All real mismatches by pattern. Safe to publish. */
  public Map<String, Long> mismatchPatterns() {
    return mismatchPatterns;
  }

  /** Real mismatches that are not explained by a mismatch they read, by pattern. */
  public Map<String, Long> rootPatterns() {
    return rootPatterns;
  }

  public long rootMismatches() {
    return rootPatterns.values().stream().mapToLong(Long::longValue).sum();
  }

  public List<Mismatch> mismatches() {
    return mismatches;
  }
}
