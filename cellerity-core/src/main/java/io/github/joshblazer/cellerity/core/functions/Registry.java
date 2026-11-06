package io.github.joshblazer.cellerity.core.functions;

import java.util.Map;

/** Registers functions under their upper-case names. */
final class Registry {
  private final Map<String, FunctionDef> functions;

  Registry(Map<String, FunctionDef> functions) {
    this.functions = functions;
  }

  void scalar(String name, int min, int max, FunctionDef.Body body) {
    add(new FunctionDef(name, min, max, FunctionDef.Mode.SCALAR, body));
  }

  void raw(String name, int min, int max, FunctionDef.Body body) {
    add(new FunctionDef(name, min, max, FunctionDef.Mode.RAW, body));
  }

  void array(String name, int min, int max, FunctionDef.Body body) {
    add(new FunctionDef(name, min, max, FunctionDef.Mode.ARRAY, body));
  }

  private void add(FunctionDef def) {
    if (functions.put(def.name(), def) != null) {
      throw new IllegalStateException("Registered twice: " + def.name());
    }
  }
}
