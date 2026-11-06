package io.github.joshblazer.cellerity.core;

import java.util.Locale;

/** Excel's operators on scalars. They return error values rather than throw. */
public final class Operators {
  private static final double NEAR_ZERO = Math.scalb(1.0, -48);

  private Operators() {}

  /** Applies an operator, lifting it over grids. */
  public static Value binary(BinOp op, Value left, Value right, FunctionContext context) {
    return Lift.binary(left, right, context, (a, b) -> scalar(op, a, b));
  }

  /**
   * Excel's snap to zero. When addition or subtraction is the last operation of a formula, a result
   * that is tiny next to its operands is noise from binary fractions, and Excel returns 0. So
   * {@code =0.5-0.4-0.1} is 0, while {@code =(0.5-0.4-0.1)} is not. Both engines must call this for
   * such a root operator, and for no other. The threshold is an evidence-based estimate (ADR-0015).
   */
  public static Value finalAddOrSubtract(
      Value left, Value right, Value result, FunctionContext context) {
    if (!(result instanceof Value.Num number) || number.value() == 0) {
      return result;
    }
    try {
      double x = Coercion.toNumber(Coercion.intersect(left, context));
      double y = Coercion.toNumber(Coercion.intersect(right, context));
      return Math.abs(number.value()) < Math.max(Math.abs(x), Math.abs(y)) * NEAR_ZERO
          ? new Value.Num(0)
          : result;
    } catch (FunctionError e) {
      return result;
    }
  }

  /** Applies an operator to two scalars. */
  public static Value scalar(BinOp op, Value a, Value b) {
    if (a instanceof Value.Err) {
      return a;
    }
    if (b instanceof Value.Err) {
      return b;
    }
    return switch (op) {
      case ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER -> arithmetic(op, a, b);
      case CONCAT -> new Value.Text(Coercion.toText(a) + Coercion.toText(b));
      case EQ -> new Value.Bool(compare(a, b) == 0);
      case NE -> new Value.Bool(compare(a, b) != 0);
      case LT -> new Value.Bool(compare(a, b) < 0);
      case LE -> new Value.Bool(compare(a, b) <= 0);
      case GT -> new Value.Bool(compare(a, b) > 0);
      case GE -> new Value.Bool(compare(a, b) >= 0);
    };
  }

  private static Value arithmetic(BinOp op, Value a, Value b) {
    double x;
    double y;
    try {
      x = Coercion.toNumber(a);
      y = Coercion.toNumber(b);
    } catch (FunctionError e) {
      return new Value.Err(e.error());
    }
    return number(apply(op, x, y));
  }

  /** The arithmetic itself, with NaN standing for an invalid result. */
  public static double apply(BinOp op, double x, double y) {
    return switch (op) {
      case ADD -> x + y;
      case SUBTRACT -> x - y;
      case MULTIPLY -> x * y;
      case DIVIDE -> y == 0 ? Double.NEGATIVE_INFINITY : x / y;
      case POWER -> x == 0 && y == 0 ? Double.NaN : Math.pow(x, y);
      default -> throw new IllegalArgumentException(op.name());
    };
  }

  private static Value number(double value) {
    if (value == Double.NEGATIVE_INFINITY) {
      return new Value.Err(ExcelError.DIV0);
    }
    return Double.isNaN(value) || Double.isInfinite(value)
        ? new Value.Err(ExcelError.NUM)
        : new Value.Num(value);
  }

  /** Excel's order across types: numbers, then text, then booleans. Blank adopts the other type. */
  public static int compare(Value a, Value b) {
    Value x = a instanceof Value.Blank ? blankAs(b) : a;
    Value y = b instanceof Value.Blank ? blankAs(a) : b;
    int rankX = rank(x);
    int rankY = rank(y);
    if (rankX != rankY) {
      return Integer.compare(rankX, rankY);
    }
    return switch (x) {
      case Value.Num n -> Double.compare(n.value() + 0.0, ((Value.Num) y).value() + 0.0);
      case Value.Text t ->
          Integer.signum(
              t.value()
                  .toLowerCase(Locale.ROOT)
                  .compareTo(((Value.Text) y).value().toLowerCase(Locale.ROOT)));
      case Value.Bool bool -> Boolean.compare(bool.value(), ((Value.Bool) y).value());
      default -> 0;
    };
  }

  private static Value blankAs(Value other) {
    return switch (other) {
      case Value.Text t -> new Value.Text("");
      case Value.Bool b -> new Value.Bool(false);
      default -> new Value.Num(0);
    };
  }

  private static int rank(Value value) {
    return switch (value) {
      case Value.Num n -> 0;
      case Value.Text t -> 1;
      case Value.Bool b -> 2;
      default -> 3;
    };
  }

  public static Value negate(Value operand, FunctionContext context) {
    return Lift.unary(operand, context, Operators::negateScalar);
  }

  public static Value plus(Value operand, FunctionContext context) {
    return Lift.prepare(operand, context);
  }

  public static Value percent(Value operand, FunctionContext context) {
    return Lift.unary(operand, context, v -> scalar(BinOp.DIVIDE, v, new Value.Num(100)));
  }

  private static Value negateScalar(Value value) {
    try {
      return new Value.Num(-Coercion.toNumber(value));
    } catch (FunctionError e) {
      return new Value.Err(e.error());
    }
  }
}
