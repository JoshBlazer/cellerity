package io.github.joshblazer.cellerity.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import io.github.joshblazer.cellerity.core.BinOp;
import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.Value;
import io.github.joshblazer.cellerity.core.functions.FunctionDef;
import io.github.joshblazer.cellerity.core.functions.Functions;
import io.github.joshblazer.cellerity.formula.Expr;
import io.github.joshblazer.cellerity.interp.FormulaEngine;
import io.github.joshblazer.cellerity.interp.Interpreter;
import io.github.joshblazer.cellerity.interp.Workspace;
import io.github.joshblazer.cellerity.truffle.Nodes.FormulaNode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Evaluates formulas as Truffle nodes. See {@code docs/design/truffle.md}. One engine serves one
 * workbook, as references to named sheets are resolved when nodes are built.
 */
public final class TruffleEngine implements FormulaEngine {
  private final Interpreter interpreter = new Interpreter();
  // One Expr can serve many cells, and comparing trees by value would be slow.
  private final IdentityHashMap<Expr, CallTarget> scalarTargets = new IdentityHashMap<>();
  private final IdentityHashMap<Expr, CallTarget> arrayTargets = new IdentityHashMap<>();
  private long nodes;
  private long delegated;

  @Override
  public Value evaluate(
      Expr expr, int sheet, int column, int row, boolean arrayFormula, Workspace workspace) {
    IdentityHashMap<Expr, CallTarget> targets = arrayFormula ? arrayTargets : scalarTargets;
    CallTarget target = targets.get(expr);
    if (target == null) {
      target = new FormulaRoot(root(expr, arrayFormula, workspace), arrayFormula).getCallTarget();
      targets.put(expr, target);
    }
    FunctionContext scalar = interpreter.context(workspace, sheet, column, row, arrayFormula);
    FunctionContext array =
        arrayFormula ? scalar : interpreter.context(workspace, sheet, column, row, true);
    return (Value) target.call(scalar, array);
  }

  /** How many nodes have been built. */
  public long nodes() {
    return nodes;
  }

  /** How many of those hand a subtree to the interpreter. */
  public long delegatedNodes() {
    return delegated;
  }

  /** The Truffle runtime in use. Only an optimizing one can JIT-compile (ADR-0006). */
  public static String runtimeName() {
    return Truffle.getRuntime().getName();
  }

  private static final class FormulaRoot extends RootNode {
    private final boolean array;
    @Child private FormulaNode body;

    FormulaRoot(FormulaNode body, boolean array) {
      super(null, new FrameDescriptor());
      this.body = body;
      this.array = array;
    }

    @Override
    public Object execute(VirtualFrame frame) {
      Value result = body.execute(frame);
      if (!array
          && (result instanceof Value.Num
              || result instanceof Value.Text
              || result instanceof Value.Bool
              || result instanceof Value.Err)) {
        return result;
      }
      return finish(result, FormulaNode.context(frame, array));
    }

    @TruffleBoundary
    private Value finish(Value result, FunctionContext context) {
      return array ? Interpreter.finishArray(result) : Interpreter.finish(result, context);
    }

    @Override
    public String getName() {
      return "formula";
    }
  }

  private FormulaNode root(Expr expr, boolean array, Workspace workspace) {
    if (!array
        && expr instanceof Expr.Binary b
        && (b.op() == Expr.BinaryOp.ADD || b.op() == Expr.BinaryOp.SUBTRACT)) {
      nodes++;
      return new Nodes.FinalAddOrSubtract(
          b.op() == Expr.BinaryOp.ADD ? BinOp.ADD : BinOp.SUBTRACT,
          build(b.left(), false, false, workspace),
          build(b.right(), false, false, workspace));
    }
    return build(expr, array, false, workspace);
  }

  /**
   * Builds the node for an expression.
   *
   * @param array whether it evaluates in array mode
   * @param wantsValue whether a single cell here may be read as a value, not kept as a reference
   */
  private FormulaNode build(Expr expr, boolean array, boolean wantsValue, Workspace workspace) {
    return switch (expr) {
      case Expr.Num n -> constant(new Value.Num(n.value()));
      case Expr.Str s -> constant(new Value.Text(s.value()));
      case Expr.Bool b -> constant(new Value.Bool(b.value()));
      case Expr.Err e -> constant(new Value.Err(e.error()));
      case Expr.Missing m -> constant(Value.BLANK);
      case Expr.Paren p -> build(p.inner(), array, wantsValue, workspace);
      case Expr.Cell c -> cell(c, array, wantsValue, workspace);
      case Expr.Area a -> area(a, array, workspace);
      case Expr.Unary u -> {
        nodes++;
        yield new Nodes.Unary(
            u.op() == Expr.UnaryOp.NEGATE ? Nodes.Unary.Kind.NEGATE : Nodes.Unary.Kind.PLUS,
            build(u.operand(), array, !array, workspace),
            array);
      }
      case Expr.Percent p -> {
        nodes++;
        yield new Nodes.Unary(
            Nodes.Unary.Kind.PERCENT, build(p.operand(), array, !array, workspace), array);
      }
      case Expr.Binary b -> binary(b, array, workspace);
      case Expr.Call c -> call(c, array, workspace);
      default -> delegate(expr, array);
    };
  }

  private FormulaNode constant(Value value) {
    nodes++;
    return new Nodes.Constant(value);
  }

  private FormulaNode delegate(Expr expr, boolean array) {
    nodes++;
    delegated++;
    return new Nodes.Delegate(expr, interpreter, array);
  }

  private FormulaNode cell(Expr.Cell cell, boolean array, boolean wantsValue, Workspace workspace) {
    if (cell.scope().external() || cell.scope().threeD()) {
      return delegate(cell, array);
    }
    OptionalInt sheet = sheet(cell.scope(), workspace);
    if (sheet.isEmpty()) {
      return constant(new Value.Err(ExcelError.REF));
    }
    nodes++;
    int column = cell.at().col();
    int row = cell.at().row();
    return wantsValue
        ? new Nodes.ScalarCell(sheet.getAsInt(), column, row)
        : new Nodes.Reference(sheet.getAsInt(), column, row, column, row);
  }

  private FormulaNode area(Expr.Area area, boolean array, Workspace workspace) {
    if (area.scope().external() || area.scope().threeD()) {
      return delegate(area, array);
    }
    OptionalInt sheet = sheet(area.scope(), workspace);
    if (sheet.isEmpty()) {
      return constant(new Value.Err(ExcelError.REF));
    }
    nodes++;
    Expr.Endpoint from = area.from();
    Expr.Endpoint to = area.to();
    return new Nodes.Reference(
        sheet.getAsInt(),
        from.wholeRow() ? 1 : from.col(),
        from.wholeColumn() ? 1 : from.row(),
        to.wholeRow() ? io.github.joshblazer.cellerity.core.CellRef.MAX_COLUMN : to.col(),
        to.wholeColumn() ? io.github.joshblazer.cellerity.core.CellRef.MAX_ROW : to.row());
  }

  /** The named sheet, -1 for the formula's own, or empty if there is no such sheet. */
  private static OptionalInt sheet(Expr.Scope scope, Workspace workspace) {
    return scope.sheet().isPresent()
        ? workspace.sheetIndex(scope.sheet().get())
        : OptionalInt.of(-1);
  }

  private FormulaNode binary(Expr.Binary b, boolean array, Workspace workspace) {
    Optional<BinOp> arithmetic =
        switch (b.op()) {
          case ADD -> Optional.of(BinOp.ADD);
          case SUBTRACT -> Optional.of(BinOp.SUBTRACT);
          case MULTIPLY -> Optional.of(BinOp.MULTIPLY);
          case DIVIDE -> Optional.of(BinOp.DIVIDE);
          case POWER -> Optional.of(BinOp.POWER);
          default -> Optional.empty();
        };
    Optional<BinOp> comparison =
        switch (b.op()) {
          case EQ -> Optional.of(BinOp.EQ);
          case NE -> Optional.of(BinOp.NE);
          case LT -> Optional.of(BinOp.LT);
          case LE -> Optional.of(BinOp.LE);
          case GT -> Optional.of(BinOp.GT);
          case GE -> Optional.of(BinOp.GE);
          default -> Optional.empty();
        };
    if (arithmetic.isEmpty() && comparison.isEmpty() && b.op() != Expr.BinaryOp.CONCAT) {
      return delegate(b, array);
    }
    FormulaNode left = build(b.left(), array, !array, workspace);
    FormulaNode right = build(b.right(), array, !array, workspace);
    nodes++;
    if (arithmetic.isPresent()) {
      return new Nodes.Arithmetic(arithmetic.get(), left, right, array);
    }
    return comparison.isPresent()
        ? new Nodes.Comparison(comparison.get(), left, right, array)
        : new Nodes.Concat(left, right, array);
  }

  private FormulaNode call(Expr.Call call, boolean array, Workspace workspace) {
    String name = call.canonicalName();
    List<Expr> args = call.args();
    if (name.equals("IF") && !args.isEmpty() && args.size() <= 3) {
      return lazyIf(call, array, workspace);
    }
    Optional<FunctionDef> def =
        Functions.LAZY.contains(name) ? Optional.empty() : Functions.get(name);
    if (def.isEmpty()) {
      return delegate(call, array);
    }
    boolean argsInArrayMode = array || def.get().mode() == FunctionDef.Mode.ARRAY;
    FormulaNode[] nodesForArgs = new FormulaNode[args.size()];
    for (int i = 0; i < nodesForArgs.length; i++) {
      nodesForArgs[i] = build(args.get(i), argsInArrayMode, false, workspace);
    }
    nodes++;
    return new Nodes.Call(def.get(), nodesForArgs, array);
  }

  private FormulaNode lazyIf(Expr.Call call, boolean array, Workspace workspace) {
    List<Expr> args = call.args();
    FormulaNode yes =
        args.size() > 1 ? branch(args.get(1), array, workspace) : constant(new Value.Bool(true));
    FormulaNode no =
        args.size() > 2 ? branch(args.get(2), array, workspace) : constant(new Value.Bool(false));
    FormulaNode whole = new Nodes.Delegate(call, interpreter, array);
    nodes++;
    return new Nodes.If(build(args.get(0), array, !array, workspace), yes, no, whole, array);
  }

  /** An omitted branch, as in {@code IF(A1,,2)}, gives 0. */
  private FormulaNode branch(Expr expr, boolean array, Workspace workspace) {
    return expr instanceof Expr.Missing
        ? constant(new Value.Num(0))
        : build(expr, array, false, workspace);
  }
}
