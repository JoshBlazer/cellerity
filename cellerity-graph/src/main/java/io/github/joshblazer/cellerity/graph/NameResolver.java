package io.github.joshblazer.cellerity.graph;

import io.github.joshblazer.cellerity.formula.Expr;
import java.util.Optional;

/** Finds what a defined name stands for, as seen from a sheet. */
@FunctionalInterface
public interface NameResolver {
  Optional<Expr> resolve(String name, int fromSheet);
}
