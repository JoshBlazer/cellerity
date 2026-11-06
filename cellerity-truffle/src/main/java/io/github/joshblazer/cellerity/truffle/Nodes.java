package io.github.joshblazer.cellerity.truffle;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import io.github.joshblazer.cellerity.core.BinOp;
import io.github.joshblazer.cellerity.core.Coercion;
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
import io.github.joshblazer.cellerity.interp.Interpreter;

/** The nodes of a formula. See {@code docs/design/truffle.md}. */
final class Nodes {
  private Nodes() {}

  /** Any part of a formula. */
  abstract static class FormulaNode extends Node {
    abstract Value execute(VirtualFrame frame);

    /** The frame holds the evaluation's context twice: as it is, and in array mode. */
    static FunctionContext context(VirtualFrame frame, boolean array) {
      return (FunctionContext) frame.getArguments()[array ? 1 : 0];
    }
  }

  /** A literal. */
  static final class Constant extends FormulaNode {
    private final Value value;

    Constant(Value value) {
      this.value = value;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return value;
    }
  }

  /** Hands a subtree to the interpreter, for what has no node of its own. */
  static final class Delegate extends FormulaNode {
    private final Expr expr;
    private final Interpreter interpreter;
    private final boolean array;

    Delegate(Expr expr, Interpreter interpreter, boolean array) {
      this.expr = expr;
      this.interpreter = interpreter;
      this.array = array;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return run(context(frame, array));
    }

    @TruffleBoundary
    private Value run(FunctionContext context) {
      return interpreter.evaluateSubtree(expr, context);
    }
  }

  /** A cell or area kept as a reference. A negative sheet means the formula's own. */
  static final class Reference extends FormulaNode {
    private final int sheet;
    private final int firstColumn;
    private final int firstRow;
    private final int lastColumn;
    private final int lastRow;

    Reference(int sheet, int firstColumn, int firstRow, int lastColumn, int lastRow) {
      this.sheet = sheet;
      this.firstColumn = firstColumn;
      this.firstRow = firstRow;
      this.lastColumn = lastColumn;
      this.lastRow = lastRow;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return range(context(frame, false));
    }

    @TruffleBoundary
    private Value range(FunctionContext context) {
      return new RangeValue(
          context.source(),
          sheet < 0 ? context.sheet() : sheet,
          firstColumn,
          firstRow,
          lastColumn,
          lastRow);
    }
  }

  /** One cell read as a value, where an operator wants one outside an array formula. */
  static final class ScalarCell extends FormulaNode {
    private final int sheet;
    private final int column;
    private final int row;

    ScalarCell(int sheet, int column, int row) {
      this.sheet = sheet;
      this.column = column;
      this.row = row;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return read(context(frame, false));
    }

    /** A boundary, as reading may evaluate other formulas. */
    @TruffleBoundary
    private Value read(FunctionContext context) {
      return context.source().cell(sheet < 0 ? context.sheet() : sheet, column, row);
    }
  }

  /** {@code + - * / ^}, specialized on two numbers until it sees anything else. */
  static final class Arithmetic extends FormulaNode {
    private final BinOp op;
    private final boolean array;
    @Child private FormulaNode left;
    @Child private FormulaNode right;
    @CompilationFinal private boolean onlyNumbers = true;

    Arithmetic(BinOp op, FormulaNode left, FormulaNode right, boolean array) {
      this.op = op;
      this.left = left;
      this.right = right;
      this.array = array;
    }

    @Override
    Value execute(VirtualFrame frame) {
      Value a = left.execute(frame);
      Value b = right.execute(frame);
      if (onlyNumbers) {
        if (a instanceof Value.Num x && b instanceof Value.Num y) {
          return Operators.number(Operators.apply(op, x.value(), y.value()));
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        onlyNumbers = false;
      }
      return generic(op, a, b, context(frame, array));
    }
  }

  /** {@code = <> < <= > >=}, specialized on two numbers until it sees anything else. */
  static final class Comparison extends FormulaNode {
    private final BinOp op;
    private final boolean array;
    @Child private FormulaNode left;
    @Child private FormulaNode right;
    @CompilationFinal private boolean onlyNumbers = true;

    Comparison(BinOp op, FormulaNode left, FormulaNode right, boolean array) {
      this.op = op;
      this.left = left;
      this.right = right;
      this.array = array;
    }

    @Override
    Value execute(VirtualFrame frame) {
      Value a = left.execute(frame);
      Value b = right.execute(frame);
      if (onlyNumbers) {
        if (a instanceof Value.Num x && b instanceof Value.Num y) {
          return new Value.Bool(holds(Double.compare(x.value() + 0.0, y.value() + 0.0)));
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        onlyNumbers = false;
      }
      return generic(op, a, b, context(frame, array));
    }

    private boolean holds(int order) {
      return switch (op) {
        case EQ -> order == 0;
        case NE -> order != 0;
        case LT -> order < 0;
        case LE -> order <= 0;
        case GT -> order > 0;
        case GE -> order >= 0;
        default -> throw CompilerDirectives.shouldNotReachHere();
      };
    }
  }

  /** {@code &}, which has nothing to specialize on. */
  static final class Concat extends FormulaNode {
    private final boolean array;
    @Child private FormulaNode left;
    @Child private FormulaNode right;

    Concat(FormulaNode left, FormulaNode right, boolean array) {
      this.left = left;
      this.right = right;
      this.array = array;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return generic(
          BinOp.CONCAT, left.execute(frame), right.execute(frame), context(frame, array));
    }
  }

  /** A formula's root {@code +} or {@code -}, the only place the snap to zero applies. */
  static final class FinalAddOrSubtract extends FormulaNode {
    private final BinOp op;
    @Child private FormulaNode left;
    @Child private FormulaNode right;

    FinalAddOrSubtract(BinOp op, FormulaNode left, FormulaNode right) {
      this.op = op;
      this.left = left;
      this.right = right;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return snap(op, left.execute(frame), right.execute(frame), context(frame, false));
    }

    @TruffleBoundary
    private static Value snap(BinOp op, Value a, Value b, FunctionContext context) {
      return Operators.finalAddOrSubtract(a, b, Operators.binary(op, a, b, context), context);
    }
  }

  @TruffleBoundary
  static Value generic(BinOp op, Value a, Value b, FunctionContext context) {
    return Operators.binary(op, a, b, context);
  }

  /** Unary minus, unary plus, and percent. */
  static final class Unary extends FormulaNode {
    /** Which operator. */
    enum Kind {
      NEGATE,
      PLUS,
      PERCENT
    }

    private final Kind kind;
    private final boolean array;
    @Child private FormulaNode operand;

    Unary(Kind kind, FormulaNode operand, boolean array) {
      this.kind = kind;
      this.operand = operand;
      this.array = array;
    }

    @Override
    Value execute(VirtualFrame frame) {
      return apply(operand.execute(frame), context(frame, array));
    }

    @TruffleBoundary
    private Value apply(Value value, FunctionContext context) {
      return switch (kind) {
        case NEGATE -> Operators.negate(value, context);
        case PLUS -> Operators.plus(value, context);
        case PERCENT -> Operators.percent(value, context);
      };
    }
  }

  /** A call to the shared function library. */
  static final class Call extends FormulaNode {
    private final FunctionDef def;
    private final boolean array;
    @Children private final FormulaNode[] args;

    Call(FunctionDef def, FormulaNode[] args, boolean array) {
      this.def = def;
      this.args = args;
      this.array = array;
    }

    @Override
    @ExplodeLoop
    Value execute(VirtualFrame frame) {
      Value[] values = new Value[args.length];
      for (int i = 0; i < args.length; i++) {
        values[i] = args[i].execute(frame);
      }
      return call(values, context(frame, array));
    }

    @TruffleBoundary
    private Value call(Value[] values, FunctionContext context) {
      return Functions.call(def, values, context);
    }
  }

  /** A lazy {@code IF}. An array condition hands the whole call to the interpreter. */
  static final class If extends FormulaNode {
    private final boolean array;
    @Child private FormulaNode condition;
    @Child private FormulaNode yes;
    @Child private FormulaNode no;
    @Child private FormulaNode whole;
    private final CountingConditionProfile branch = CountingConditionProfile.create();

    If(FormulaNode condition, FormulaNode yes, FormulaNode no, FormulaNode whole, boolean array) {
      this.condition = condition;
      this.yes = yes;
      this.no = no;
      this.whole = whole;
      this.array = array;
    }

    @Override
    Value execute(VirtualFrame frame) {
      Value test = condition.execute(frame);
      boolean taken;
      if (test instanceof Value.Bool b) {
        taken = b.value();
      } else if (test instanceof Value.Num n) {
        taken = n.value() != 0;
      } else {
        Value prepared = prepare(test, context(frame, array));
        if (prepared instanceof Value.Grid) {
          return whole.execute(frame);
        }
        if (prepared instanceof Value.Err) {
          return prepared;
        }
        int truth = truth(prepared);
        if (truth < 0) {
          return new Value.Err(ExcelError.VALUE);
        }
        taken = truth == 1;
      }
      return branch.profile(taken) ? yes.execute(frame) : no.execute(frame);
    }

    @TruffleBoundary
    private static Value prepare(Value value, FunctionContext context) {
      return Lift.prepare(value, context);
    }

    /** 1 for true, 0 for false, and -1 if the value has no truth, as with most text. */
    @TruffleBoundary
    private static int truth(Value value) {
      try {
        return Coercion.toBool(value) ? 1 : 0;
      } catch (FunctionError e) {
        return -1;
      }
    }
  }
}
