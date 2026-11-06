package io.github.joshblazer.cellerity.core.functions;

import io.github.joshblazer.cellerity.core.ExcelError;
import io.github.joshblazer.cellerity.core.FunctionContext;
import io.github.joshblazer.cellerity.core.FunctionError;
import io.github.joshblazer.cellerity.core.Lift;
import io.github.joshblazer.cellerity.core.Value;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** The function library, shared by every evaluator. */
public final class Functions {
  /** Evaluated by the evaluators themselves, so that an untaken branch does no work. */
  public static final Set<String> LAZY = Set.of("IF", "IFERROR", "IFNA", "IFS", "SWITCH", "CHOOSE");

  /** Saved values of these cannot be reproduced, so they are not comparable. */
  public static final Set<String> VOLATILE =
      Set.of("NOW", "TODAY", "RAND", "RANDBETWEEN", "CELL", "INFO");

  private static final Map<String, FunctionDef> REGISTRY = new HashMap<>();

  static {
    Registry registry = new Registry(REGISTRY);
    MathFunctions.register(registry);
    StatFunctions.register(registry);
    LogicFunctions.register(registry);
    TextFunctions.register(registry);
    DateFunctions.register(registry);
    LookupFunctions.register(registry);
    FinanceFunctions.register(registry);
  }

  private Functions() {}

  public static Optional<FunctionDef> get(String name) {
    return Optional.ofNullable(REGISTRY.get(name));
  }

  /** Whether a call to this function can be evaluated. */
  public static boolean supported(String name) {
    return REGISTRY.containsKey(name) || LAZY.contains(name);
  }

  /** Every supported function, sorted. */
  public static Set<String> names() {
    Set<String> names = new TreeSet<>(REGISTRY.keySet());
    names.addAll(LAZY);
    return names;
  }

  /** Calls a function. Errors thrown inside become error values here. */
  public static Value call(FunctionDef def, Value[] args, FunctionContext context) {
    if (args.length < def.minArgs() || args.length > def.maxArgs()) {
      return new Value.Err(ExcelError.VALUE);
    }
    if (def.mode() == FunctionDef.Mode.SCALAR) {
      return Lift.call(args, context, scalars -> guarded(def, scalars, context));
    }
    return guarded(def, args, context);
  }

  private static Value guarded(FunctionDef def, Value[] args, FunctionContext context) {
    try {
      Value result = def.body().apply(args, context);
      if (result instanceof Value.Num number
          && (Double.isNaN(number.value()) || Double.isInfinite(number.value()))) {
        return new Value.Err(ExcelError.NUM);
      }
      return result;
    } catch (FunctionError e) {
      return new Value.Err(e.error());
    }
  }
}
