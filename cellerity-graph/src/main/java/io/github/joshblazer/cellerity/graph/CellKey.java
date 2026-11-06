package io.github.joshblazer.cellerity.graph;

import io.github.joshblazer.cellerity.core.CellRef;

/** A cell in a workbook, by sheet position. */
public record CellKey(int sheet, CellRef at) implements Comparable<CellKey> {
  @Override
  public int compareTo(CellKey other) {
    return sheet != other.sheet ? Integer.compare(sheet, other.sheet) : at.compareTo(other.at);
  }
}
