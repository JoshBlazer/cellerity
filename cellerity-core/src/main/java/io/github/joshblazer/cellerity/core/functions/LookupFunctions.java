package io.github.joshblazer.cellerity.core.functions;

import static io.github.joshblazer.cellerity.core.functions.Args.error;
import static io.github.joshblazer.cellerity.core.functions.Args.n;
import static io.github.joshblazer.cellerity.core.functions.Args.num;
import static io.github.joshblazer.cellerity.core.functions.Args.text;

import io.github.joshblazer.cellerity.core.ArrayGrid;
import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.Lift;
import io.github.joshblazer.cellerity.core.Operators;
import io.github.joshblazer.cellerity.core.RangeValue;
import io.github.joshblazer.cellerity.core.Value;
import java.util.regex.Pattern;

final class LookupFunctions {
  private LookupFunctions() {}

  /** A line of cells to search. */
  private interface Line {
    int size();

    Value at(int index);
  }

  static void register(Registry r) {
    r.raw(
        "VLOOKUP",
        3,
        4,
        (a, c) -> {
          Value.Grid table = grid(a[1]);
          int column = position(single(a[2], c));
          boolean approximate = a.length < 4 || Coercion.toBool(single(a[3], c));
          if (column > table.columns()) {
            throw error(ExcelError.REF);
          }
          Line keys = columnLine(table, 0);
          int found = find(single(a[0], c), keys, approximate ? 1 : 0);
          return found < 0 ? new Value.Err(ExcelError.NA) : table.get(found, column - 1);
        });
    r.raw(
        "HLOOKUP",
        3,
        4,
        (a, c) -> {
          Value.Grid table = grid(a[1]);
          int row = position(single(a[2], c));
          boolean approximate = a.length < 4 || Coercion.toBool(single(a[3], c));
          if (row > table.rows()) {
            throw error(ExcelError.REF);
          }
          Line keys = rowLine(table, 0);
          int found = find(single(a[0], c), keys, approximate ? 1 : 0);
          return found < 0 ? new Value.Err(ExcelError.NA) : table.get(row - 1, found);
        });
    r.array("LOOKUP", 2, 3, (a, c) -> lookup(a));
    r.array(
        "MATCH",
        2,
        3,
        (a, c) -> {
          Value.Grid g = grid(a[1]);
          if (g.rows() > 1 && g.columns() > 1) {
            throw error(ExcelError.NA);
          }
          Line line = g.columns() == 1 ? columnLine(g, 0) : rowLine(g, 0);
          int type =
              a.length > 2 && !(a[2] instanceof Value.Blank)
                  ? (int) Math.signum(Coercion.toNumber(a[2]))
                  : 1;
          int found = find(Coercion.first(a[0]), line, type);
          return found < 0 ? new Value.Err(ExcelError.NA) : num(found + 1.0);
        });
    r.array("INDEX", 2, 4, (a, c) -> index(a));
    r.raw(
        "ROW", 0, 1, (a, c) -> a.length == 0 ? num(c.row()) : num(Args.reference(a[0]).firstRow()));
    r.raw(
        "COLUMN",
        0,
        1,
        (a, c) -> a.length == 0 ? num(c.column()) : num(Args.reference(a[0]).firstColumn()));
    r.raw("ROWS", 1, 1, (a, c) -> num(grid(a[0]).rows()));
    r.raw("COLUMNS", 1, 1, (a, c) -> num(grid(a[0]).columns()));
    r.raw("OFFSET", 3, 5, (a, c) -> offset(a, c));
    r.raw(
        "INDIRECT",
        1,
        2,
        (a, c) -> {
          if (a.length > 1 && !Coercion.toBool(single(a[1], c))) {
            throw error(ExcelError.REF);
          }
          return c.indirect(Coercion.toText(single(a[0], c)));
        });
    r.scalar(
        "ADDRESS",
        2,
        5,
        (a, c) -> {
          int row = (int) n(a, 0);
          int column = (int) n(a, 1);
          if (row < 1 || row > CellRef.MAX_ROW || column < 1 || column > CellRef.MAX_COLUMN) {
            throw error(ExcelError.VALUE);
          }
          int kind = (int) n(a, 2, 1);
          boolean a1Style = n(a, 3, 1) != 0;
          if (kind < 1 || kind > 4 || !a1Style) {
            throw error(ExcelError.VALUE);
          }
          String letters = new CellRef(column, row).toA1().replaceAll("[0-9]", "");
          String address =
              (kind == 1 || kind == 3 ? "$" : "")
                  + letters
                  + (kind == 1 || kind == 2 ? "$" : "")
                  + row;
          if (a.length > 4 && !(a[4] instanceof Value.Blank)) {
            String sheet = Args.s(a, 4);
            address = "'" + sheet.replace("'", "''") + "'!" + address;
          }
          return text(address);
        });
    r.array(
        "TRANSPOSE",
        1,
        1,
        (a, c) -> {
          Value.Grid g = grid(a[0]);
          Value[] cells = new Value[g.usedRows() * g.usedColumns()];
          int rows = g.usedColumns();
          int columns = g.usedRows();
          for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
              cells[i * columns + j] = g.get(j, i);
            }
          }
          return new ArrayGrid(rows, columns, cells);
        });
  }

  private static Value single(Value value, FunctionContext context) {
    Value scalar = Lift.prepare(value, context);
    Value first = Coercion.first(scalar);
    if (first instanceof Value.Err e) {
      throw error(e.error());
    }
    return first;
  }

  private static int position(Value value) {
    int position = (int) Coercion.toNumber(value);
    if (position < 1) {
      throw error(ExcelError.VALUE);
    }
    return position;
  }

  private static Value.Grid grid(Value value) {
    if (value instanceof Value.Grid g) {
      return g;
    }
    if (value instanceof Value.Err e) {
      throw error(e.error());
    }
    return new ArrayGrid(1, 1, new Value[] {value});
  }

  private static Line columnLine(Value.Grid g, int column) {
    return new Line() {
      @Override
      public int size() {
        return g.usedRows();
      }

      @Override
      public Value at(int index) {
        return g.get(index, column);
      }
    };
  }

  private static Line rowLine(Value.Grid g, int row) {
    return new Line() {
      @Override
      public int size() {
        return g.usedColumns();
      }

      @Override
      public Value at(int index) {
        return g.get(row, index);
      }
    };
  }

  /**
   * Finds a key. Type 0 is exact, with wildcards for text. Types 1 and -1 find the last value not
   * above, or the last value not below, the key. Type 1 is a binary search, as Excel's appears to
   * be, so unsorted data gives the same kind of answer rather than the linear one.
   */
  private static int find(Value key, Line line, int type) {
    if (key instanceof Value.Err e) {
      throw error(e.error());
    }
    Value target = key instanceof Value.Blank ? new Value.Num(0) : key;
    if (type == 0) {
      return exact(target, line);
    }
    if (type < 0) {
      int best = -1;
      for (int i = 0; i < line.size(); i++) {
        Value v = line.at(i);
        if (sameType(v, target) && Operators.compare(v, target) >= 0) {
          best = i;
        } else if (sameType(v, target)) {
          break;
        }
      }
      return best;
    }
    int low = 0;
    int high = line.size() - 1;
    int best = -1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      int probe = mid;
      while (probe >= low && !sameType(line.at(probe), target)) {
        probe--;
      }
      if (probe < low) {
        low = mid + 1;
        continue;
      }
      if (Operators.compare(line.at(probe), target) <= 0) {
        best = probe;
        low = mid + 1;
      } else {
        high = probe - 1;
      }
    }
    return best;
  }

  private static int exact(Value target, Line line) {
    if (target instanceof Value.Text t && Criteria.hasWildcard(t.value())) {
      Pattern pattern = Criteria.wildcard(t.value());
      for (int i = 0; i < line.size(); i++) {
        if (line.at(i) instanceof Value.Text v && pattern.matcher(v.value()).matches()) {
          return i;
        }
      }
      return -1;
    }
    for (int i = 0; i < line.size(); i++) {
      Value v = line.at(i);
      if (sameType(v, target) && Operators.compare(v, target) == 0) {
        return i;
      }
    }
    return -1;
  }

  private static boolean sameType(Value a, Value b) {
    return (a instanceof Value.Num && b instanceof Value.Num)
        || (a instanceof Value.Text && b instanceof Value.Text)
        || (a instanceof Value.Bool && b instanceof Value.Bool);
  }

  private static Value lookup(Value[] a) {
    Value key = Coercion.first(a[0]);
    Value.Grid source = grid(a[1]);
    Line keys;
    Value.Grid results;
    if (a.length == 3) {
      Value.Grid vector = grid(a[1]);
      keys = vector.columns() == 1 ? columnLine(vector, 0) : rowLine(vector, 0);
      results = grid(a[2]);
    } else if (source.rows() >= source.columns()) {
      keys = columnLine(source, 0);
      results = null;
    } else {
      keys = rowLine(source, 0);
      results = null;
    }
    int found = find(key, keys, 1);
    if (found < 0) {
      return new Value.Err(ExcelError.NA);
    }
    if (results != null) {
      return results.columns() == 1 ? results.get(found, 0) : results.get(0, found);
    }
    return source.rows() >= source.columns()
        ? source.get(found, source.columns() - 1)
        : source.get(source.rows() - 1, found);
  }

  private static Value index(Value[] a) {
    if (a[0] instanceof Value.Err e) {
      throw error(e.error());
    }
    Value.Grid g = grid(a[0]);
    int rowArg = (int) Coercion.toNumber(a[1]);
    int columnArg;
    if (a.length > 2 && !(a[2] instanceof Value.Blank)) {
      columnArg = (int) Coercion.toNumber(a[2]);
    } else if (a.length > 2 || g.columns() == 1) {
      columnArg = g.columns() == 1 ? 1 : 0;
    } else if (g.rows() == 1) {
      columnArg = rowArg;
      rowArg = 1;
    } else {
      columnArg = 0;
    }
    if (a.length > 3 && !(a[3] instanceof Value.Blank) && Coercion.toNumber(a[3]) != 1) {
      throw error(ExcelError.REF);
    }
    if (rowArg < 0 || columnArg < 0 || rowArg > g.rows() || columnArg > g.columns()) {
      throw error(ExcelError.REF);
    }
    if (g instanceof RangeValue range) {
      int firstRow = rowArg == 0 ? range.firstRow() : range.firstRow() + rowArg - 1;
      int lastRow = rowArg == 0 ? range.lastRow() : firstRow;
      int firstColumn = columnArg == 0 ? range.firstColumn() : range.firstColumn() + columnArg - 1;
      int lastColumn = columnArg == 0 ? range.lastColumn() : firstColumn;
      return new RangeValue(
          range.source(), range.sheet(), firstColumn, firstRow, lastColumn, lastRow);
    }
    if (rowArg > 0 && columnArg > 0) {
      return g.get(rowArg - 1, columnArg - 1);
    }
    int rows = rowArg == 0 ? g.rows() : 1;
    int columns = columnArg == 0 ? g.columns() : 1;
    Value[] cells = new Value[rows * columns];
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < columns; j++) {
        cells[i * columns + j] =
            g.get(rowArg == 0 ? i : rowArg - 1, columnArg == 0 ? j : columnArg - 1);
      }
    }
    return new ArrayGrid(rows, columns, cells);
  }

  private static Value offset(Value[] a, FunctionContext c) {
    RangeValue base = Args.reference(a[0]);
    int rowShift = (int) Coercion.toNumber(single(a[1], c));
    int columnShift = (int) Coercion.toNumber(single(a[2], c));
    int height =
        a.length > 3 && !(a[3] instanceof Value.Blank)
            ? (int) Coercion.toNumber(single(a[3], c))
            : base.rows();
    int width =
        a.length > 4 && !(a[4] instanceof Value.Blank)
            ? (int) Coercion.toNumber(single(a[4], c))
            : base.columns();
    if (height == 0 || width == 0) {
      throw error(ExcelError.REF);
    }
    int firstRow = base.firstRow() + rowShift;
    int firstColumn = base.firstColumn() + columnShift;
    int lastRow = firstRow + height + (height > 0 ? -1 : 1);
    int lastColumn = firstColumn + width + (width > 0 ? -1 : 1);
    if (Math.min(firstRow, lastRow) < 1
        || Math.min(firstColumn, lastColumn) < 1
        || Math.max(firstRow, lastRow) > CellRef.MAX_ROW
        || Math.max(firstColumn, lastColumn) > CellRef.MAX_COLUMN) {
      throw error(ExcelError.REF);
    }
    return new RangeValue(base.source(), base.sheet(), firstColumn, firstRow, lastColumn, lastRow);
  }
}
