package io.github.joshblazer.cellerity.interp;

import io.github.joshblazer.cellerity.core.ArrayGrid;
import io.github.joshblazer.cellerity.core.BinOp;
import io.github.joshblazer.cellerity.core.CellRef;
import io.github.joshblazer.cellerity.core.CellSource;
import io.github.joshblazer.cellerity.core.Coercion;
import io.github.joshblazer.cellerity.core.DateSystem;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.FunctionError;
import io.github.joshblazer.cellerity.core.Lift;
import io.github.joshblazer.cellerity.core.Operators;
import io.github.joshblazer.cellerity.core.RangeValue;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.core.functions.FunctionDef;
import io.github.joshblazer.cellerity.core.functions.Functions;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.formula.FormulaParser;
import io.github.joshblazer.cellerity.formula.ParseResult;
import io.github.joshblazer.cellerity.formula.ReferenceShifter;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The reference evaluator: a plain tree walk. It favours being obviously correct over speed, and
 * every other engine is tested against it. See {@code docs/design/evaluator.md}.
 */
public final class Interpreter implements FormulaEngine {
  private static final Value ERR_REF = new Value.Err(ExcelError.REF);
  private static final Value ERR_NAME = new Value.Err(ExcelError.NAME);
  private static final Value ERR_NULL = new Value.Err(ExcelError.NULL);
  private static final Value ERR_VALUE = new Value.Err(ExcelError.VALUE);
  private static final int MAX_NAME_DEPTH = 32;

  @Override
  public Value evaluate(
      Expr expr, int sheet, int column, int row, boolean arrayFormula, Workspace workspace) {
    Frame frame = new Frame(workspace, sheet, column, row, arrayFormula, 0);
    Value result = eval(expr, frame);
    return arrayFormula ? finishArray(result) : finish(result, frame);
  }

  private static Value finish(Value result, Frame frame) {
    Value scalar = Coercion.intersect(result, frame);
    return scalar instanceof Value.Blank ? new Value.Num(0) : scalar;
  }

  private static Value finishArray(Value result) {
    if (!(result instanceof Value.Grid grid)) {
      return result instanceof Value.Blank ? new Value.Num(0) : result;
    }
    int rows = grid.usedRows();
    int columns = grid.usedColumns();
    Value[] cells = new Value[rows * columns];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < columns; c++) {
        Value v = grid.get(r, c);
        cells[r * columns + c] = v instanceof Value.Blank ? new Value.Num(0) : v;
      }
    }
    return new ArrayGrid(rows, columns, cells);
  }

  /** The context of one evaluation, implementing what functions may ask. */
  private final class Frame implements FunctionContext {
    final Workspace workspace;
    final int sheet;
    final int column;
    final int row;
    final boolean arrayMode;
    final int nameDepth;

    Frame(Workspace workspace, int sheet, int column, int row, boolean arrayMode, int nameDepth) {
      this.workspace = workspace;
      this.sheet = sheet;
      this.column = column;
      this.row = row;
      this.arrayMode = arrayMode;
      this.nameDepth = nameDepth;
    }

    Frame withArrayMode() {
      return arrayMode ? this : new Frame(workspace, sheet, column, row, true, nameDepth);
    }

    Frame inName() {
      return new Frame(workspace, sheet, column, row, arrayMode, nameDepth + 1);
    }

    @Override
    public DateSystem dates() {
      return workspace.dates();
    }

    @Override
    public int sheet() {
      return sheet;
    }

    @Override
    public int column() {
      return column;
    }

    @Override
    public int row() {
      return row;
    }

    @Override
    public CellSource source() {
      return workspace;
    }

    @Override
    public boolean arrayMode() {
      return arrayMode;
    }

    @Override
    public double now() {
      return workspace.now();
    }

    @Override
    public double random() {
      return workspace.random();
    }

    @Override
    public Value indirect(String text) {
      ParseResult parsed = FormulaParser.parse(text);
      if (!parsed.ok()) {
        return ERR_REF;
      }
      Expr expr = parsed.expr();
      if (expr instanceof Expr.Cell || expr instanceof Expr.Area || expr instanceof Expr.Name) {
        Value result = eval(expr, this);
        return result instanceof RangeValue ? result : ERR_REF;
      }
      return ERR_REF;
    }
  }

  private Value eval(Expr expr, Frame frame) {
    return switch (expr) {
      case Expr.Num n -> new Value.Num(n.value());
      case Expr.Str s -> new Value.Text(s.value());
      case Expr.Bool b -> new Value.Bool(b.value());
      case Expr.Err e -> new Value.Err(e.error());
      case Expr.Missing m -> Value.BLANK;
      case Expr.Cell c -> cell(c, frame);
      case Expr.Area a -> area(a, frame);
      case Expr.BrokenRef r -> {
        checkScope(r.scope());
        yield ERR_REF;
      }
      case Expr.Name n -> name(n, frame);
      case Expr.Structured s -> throw new UnsupportedFeature("structured reference");
      case Expr.Invalid i -> throw new UnsupportedFeature("parse failure");
      case Expr.Paren p -> eval(p.inner(), frame);
      case Expr.Percent p -> Operators.percent(eval(p.operand(), frame), frame);
      case Expr.Unary u ->
          u.op() == Expr.UnaryOp.NEGATE
              ? Operators.negate(eval(u.operand(), frame), frame)
              : Operators.plus(eval(u.operand(), frame), frame);
      case Expr.Binary b -> binary(b, frame);
      case Expr.Call c -> call(c, frame);
      case Expr.ArrayLit a -> arrayLiteral(a, frame);
    };
  }

  private Value arrayLiteral(Expr.ArrayLit literal, Frame frame) {
    int rows = literal.rows().size();
    int columns = literal.rows().get(0).size();
    Value[] cells = new Value[rows * columns];
    for (int r = 0; r < rows; r++) {
      List<Expr> row = literal.rows().get(r);
      if (row.size() != columns) {
        throw new UnsupportedFeature("uneven array constant");
      }
      for (int c = 0; c < columns; c++) {
        cells[r * columns + c] = eval(row.get(c), frame);
      }
    }
    return new ArrayGrid(rows, columns, cells);
  }

  private static void checkScope(Expr.Scope scope) {
    if (scope.external()) {
      throw new UnsupportedFeature("external reference");
    }
    if (scope.threeD()) {
      throw new UnsupportedFeature("3D reference");
    }
  }

  private static OptionalInt targetSheet(Expr.Scope scope, Frame frame) {
    checkScope(scope);
    return scope.sheet().isPresent()
        ? frame.workspace.sheetIndex(scope.sheet().get())
        : OptionalInt.of(frame.sheet);
  }

  private static Value cell(Expr.Cell cell, Frame frame) {
    OptionalInt sheet = targetSheet(cell.scope(), frame);
    if (sheet.isEmpty()) {
      return ERR_REF;
    }
    return new RangeValue(
        frame.workspace,
        sheet.getAsInt(),
        cell.at().col(),
        cell.at().row(),
        cell.at().col(),
        cell.at().row());
  }

  private static Value area(Expr.Area area, Frame frame) {
    OptionalInt sheet = targetSheet(area.scope(), frame);
    if (sheet.isEmpty()) {
      return ERR_REF;
    }
    Expr.Endpoint from = area.from();
    Expr.Endpoint to = area.to();
    return new RangeValue(
        frame.workspace,
        sheet.getAsInt(),
        from.wholeRow() ? 1 : from.col(),
        from.wholeColumn() ? 1 : from.row(),
        to.wholeRow() ? CellRef.MAX_COLUMN : to.col(),
        to.wholeColumn() ? CellRef.MAX_ROW : to.row());
  }

  private Value name(Expr.Name name, Frame frame) {
    checkScope(name.scope());
    if (frame.nameDepth >= MAX_NAME_DEPTH) {
      throw new UnsupportedFeature("names nested too deeply");
    }
    int from = frame.sheet;
    if (name.scope().sheet().isPresent()) {
      OptionalInt sheet = frame.workspace.sheetIndex(name.scope().sheet().get());
      if (sheet.isEmpty()) {
        return ERR_REF;
      }
      from = sheet.getAsInt();
    }
    Optional<Expr> target = frame.workspace.name(name.name(), from);
    if (target.isEmpty()) {
      return ERR_NAME;
    }
    Expr shifted = ReferenceShifter.shift(target.get(), frame.column - 1, frame.row - 1);
    return eval(shifted, frame.inName());
  }

  private Value binary(Expr.Binary b, Frame frame) {
    return switch (b.op()) {
      case RANGE -> rangeOperator(eval(b.left(), frame), eval(b.right(), frame));
      case INTERSECT -> intersection(eval(b.left(), frame), eval(b.right(), frame));
      case UNION -> throw new UnsupportedFeature("union reference");
      case ADD -> op(BinOp.ADD, b, frame);
      case SUBTRACT -> op(BinOp.SUBTRACT, b, frame);
      case MULTIPLY -> op(BinOp.MULTIPLY, b, frame);
      case DIVIDE -> op(BinOp.DIVIDE, b, frame);
      case POWER -> op(BinOp.POWER, b, frame);
      case CONCAT -> op(BinOp.CONCAT, b, frame);
      case EQ -> op(BinOp.EQ, b, frame);
      case NE -> op(BinOp.NE, b, frame);
      case LT -> op(BinOp.LT, b, frame);
      case LE -> op(BinOp.LE, b, frame);
      case GT -> op(BinOp.GT, b, frame);
      case GE -> op(BinOp.GE, b, frame);
    };
  }

  private Value op(BinOp op, Expr.Binary b, Frame frame) {
    return Operators.binary(op, eval(b.left(), frame), eval(b.right(), frame), frame);
  }

  private static Value rangeOperator(Value left, Value right) {
    if (left instanceof Value.Err) {
      return left;
    }
    if (right instanceof Value.Err) {
      return right;
    }
    if (!(left instanceof RangeValue a)
        || !(right instanceof RangeValue b)
        || a.sheet() != b.sheet()) {
      return ERR_VALUE;
    }
    return new RangeValue(
        a.source(),
        a.sheet(),
        Math.min(a.firstColumn(), b.firstColumn()),
        Math.min(a.firstRow(), b.firstRow()),
        Math.max(a.lastColumn(), b.lastColumn()),
        Math.max(a.lastRow(), b.lastRow()));
  }

  private static Value intersection(Value left, Value right) {
    if (left instanceof Value.Err) {
      return left;
    }
    if (right instanceof Value.Err) {
      return right;
    }
    if (!(left instanceof RangeValue a) || !(right instanceof RangeValue b)) {
      return ERR_VALUE;
    }
    int firstColumn = Math.max(a.firstColumn(), b.firstColumn());
    int firstRow = Math.max(a.firstRow(), b.firstRow());
    int lastColumn = Math.min(a.lastColumn(), b.lastColumn());
    int lastRow = Math.min(a.lastRow(), b.lastRow());
    if (a.sheet() != b.sheet() || firstColumn > lastColumn || firstRow > lastRow) {
      return ERR_NULL;
    }
    return new RangeValue(a.source(), a.sheet(), firstColumn, firstRow, lastColumn, lastRow);
  }

  private Value call(Expr.Call call, Frame frame) {
    String name = call.canonicalName();
    if (Functions.LAZY.contains(name)) {
      return lazy(name, call.args(), frame);
    }
    Optional<FunctionDef> def = Functions.get(name);
    if (def.isEmpty()) {
      throw new UnsupportedFeature("function " + name);
    }
    Frame argFrame = def.get().mode() == FunctionDef.Mode.ARRAY ? frame.withArrayMode() : frame;
    Value[] args = new Value[call.args().size()];
    for (int i = 0; i < args.length; i++) {
      args[i] = eval(call.args().get(i), argFrame);
    }
    return Functions.call(def.get(), args, frame);
  }

  private Value lazy(String name, List<Expr> args, Frame frame) {
    try {
      return switch (name) {
        case "IF" -> lazyIf(args, frame);
        case "IFERROR" -> lazyIfError(args, frame, false);
        case "IFNA" -> lazyIfError(args, frame, true);
        case "IFS" -> lazyIfs(args, frame);
        case "SWITCH" -> lazySwitch(args, frame);
        case "CHOOSE" -> lazyChoose(args, frame);
        default -> throw new IllegalStateException(name);
      };
    } catch (FunctionError e) {
      return new Value.Err(e.error());
    }
  }

  private Value lazyIf(List<Expr> args, Frame frame) {
    if (args.isEmpty() || args.size() > 3) {
      return ERR_VALUE;
    }
    Value condition = Lift.prepare(eval(args.get(0), frame), frame);
    if (condition instanceof Value.Grid) {
      Value yes = args.size() > 1 ? eval(args.get(1), frame) : new Value.Bool(true);
      Value no = args.size() > 2 ? eval(args.get(2), frame) : new Value.Bool(false);
      return Lift.call(
          new Value[] {condition, yes, no}, frame, v -> Lift.element(pick(v[0], v[1], v[2]), 0, 0));
    }
    if (condition instanceof Value.Err) {
      return condition;
    }
    if (Coercion.toBool(condition)) {
      return args.size() > 1
          ? blankAsZero(eval(args.get(1), frame), args.get(1))
          : new Value.Bool(true);
    }
    return args.size() > 2
        ? blankAsZero(eval(args.get(2), frame), args.get(2))
        : new Value.Bool(false);
  }

  /** An omitted branch, as in {@code IF(A1,,2)}, gives 0. */
  private static Value blankAsZero(Value value, Expr source) {
    return source instanceof Expr.Missing ? new Value.Num(0) : value;
  }

  private static Value pick(Value condition, Value yes, Value no) {
    if (condition instanceof Value.Err) {
      return condition;
    }
    try {
      return Coercion.toBool(condition) ? yes : no;
    } catch (FunctionError e) {
      return new Value.Err(e.error());
    }
  }

  private Value lazyIfError(List<Expr> args, Frame frame, boolean onlyNa) {
    if (args.size() != 2) {
      return ERR_VALUE;
    }
    Value value = Lift.prepare(eval(args.get(0), frame), frame);
    if (value instanceof Value.Grid grid) {
      Value fallback = eval(args.get(1), frame);
      return Lift.call(
          new Value[] {grid, fallback}, frame, v -> isCaught(v[0], onlyNa) ? v[1] : v[0]);
    }
    return isCaught(value, onlyNa) ? eval(args.get(1), frame) : value;
  }

  private static boolean isCaught(Value value, boolean onlyNa) {
    return value instanceof Value.Err e && (!onlyNa || e.error() == ExcelError.NA);
  }

  private Value lazyIfs(List<Expr> args, Frame frame) {
    if (args.size() % 2 != 0) {
      return ERR_VALUE;
    }
    for (int i = 0; i < args.size(); i += 2) {
      Value condition = Coercion.first(Lift.prepare(eval(args.get(i), frame), frame));
      if (condition instanceof Value.Err) {
        return condition;
      }
      if (Coercion.toBool(condition)) {
        return eval(args.get(i + 1), frame);
      }
    }
    return new Value.Err(ExcelError.NA);
  }

  private Value lazySwitch(List<Expr> args, Frame frame) {
    if (args.size() < 3) {
      return ERR_VALUE;
    }
    Value subject = Coercion.first(Lift.prepare(eval(args.get(0), frame), frame));
    if (subject instanceof Value.Err) {
      return subject;
    }
    int i = 1;
    for (; i + 1 < args.size(); i += 2) {
      Value candidate = Coercion.first(Lift.prepare(eval(args.get(i), frame), frame));
      if (candidate instanceof Value.Err) {
        return candidate;
      }
      if (Operators.compare(subject, candidate) == 0) {
        return eval(args.get(i + 1), frame);
      }
    }
    return i < args.size() ? eval(args.get(i), frame) : new Value.Err(ExcelError.NA);
  }

  private Value lazyChoose(List<Expr> args, Frame frame) {
    if (args.size() < 2) {
      return ERR_VALUE;
    }
    Value index = Coercion.first(Lift.prepare(eval(args.get(0), frame), frame));
    if (index instanceof Value.Err) {
      return index;
    }
    double position = Math.floor(Coercion.toNumber(index));
    if (position < 1 || position >= args.size()) {
      return ERR_VALUE;
    }
    return eval(args.get((int) position), frame);
  }
}
