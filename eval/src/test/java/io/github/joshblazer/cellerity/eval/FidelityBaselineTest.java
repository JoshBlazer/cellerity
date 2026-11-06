package io.github.joshblazer.cellerity.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.joshblazer.cellerity.interp.Coverage;
import io.github.joshblazer.cellerity.interp.VerifyResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FidelityBaselineTest {
  @TempDir Path dir;

  private boolean passes(String floors) throws IOException {
    Path file = dir.resolve("baseline.properties");
    Files.writeString(file, floors);
    return Fidelity.checkBaseline(file, new VerifyResult(0), new Coverage());
  }

  @Test
  void aMetricBelowItsFloorFails() throws IOException {
    // An empty result has fidelity 0, so any positive floor must trip.
    assertFalse(passes("minFidelity=0.5\n"));
    assertFalse(passes("minCompared=1\n"));
    assertFalse(passes("minCoverage=0.5\n"));
  }

  @Test
  void aMetricAtItsFloorPasses() throws IOException {
    assertTrue(passes("minFidelity=0\nminCompared=0\nminCoverage=0\nminFunctionCoverage=1\n"));
  }
}
