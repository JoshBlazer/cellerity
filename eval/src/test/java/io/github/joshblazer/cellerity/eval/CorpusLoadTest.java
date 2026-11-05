package io.github.joshblazer.cellerity.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusLoadTest {
  private static final Path SAMPLE = Path.of("../corpus/samples/synthetic/sample.xlsx");

  @TempDir Path dir;

  @Test
  void countsLoadedFilesAndTheirContents() throws Exception {
    Files.copy(SAMPLE, dir.resolve("a.xlsx"));
    CorpusResult r = CorpusLoad.run(List.of(dir), Duration.ofSeconds(30));
    assertEquals(1, r.corpusFiles());
    assertEquals(1, r.loaded());
    assertEquals(0, r.crashed());
    assertEquals(2, r.sheets());
    assertEquals(10, r.cells());
    assertEquals(2, r.formulaCells());
    assertEquals(2, r.formulaCellsWithCachedValue());
    assertEquals(3, r.definedNames());
    assertEquals(1, r.writers().get("Cellerity MakeSample (synthetic)"));
    assertTrue(r.failures().isEmpty());
  }

  @Test
  void badFilesAreRejectionsNotCrashes() throws Exception {
    Files.writeString(dir.resolve("junk.xlsx"), "not a workbook");
    CorpusResult r = CorpusLoad.run(List.of(dir), Duration.ofSeconds(30));
    assertEquals(1, r.corpusFiles());
    assertEquals(0, r.loaded());
    assertEquals(0, r.crashed());
    assertEquals(1, r.rejected().get("UNSUPPORTED_FORMAT"));
    assertEquals("junk.xlsx", r.failures().get(0).file());
    assertEquals("UNSUPPORTED_FORMAT", r.failures().get(0).outcome());
  }

  @Test
  void onlyWorkbookExtensionsAreConsidered() throws Exception {
    Files.writeString(dir.resolve("notes.txt"), "hello");
    assertEquals(0, CorpusLoad.run(List.of(dir), Duration.ofSeconds(30)).corpusFiles());
  }

  @Test
  void searchesSubdirectoriesAndAllRoots() throws Exception {
    Path a = Files.createDirectory(dir.resolve("a"));
    Path b = Files.createDirectories(dir.resolve("b/nested"));
    Files.copy(SAMPLE, a.resolve("1.xlsx"));
    Files.copy(SAMPLE, b.resolve("2.xlsm"));
    assertEquals(
        2, CorpusLoad.run(List.of(a, dir.resolve("b")), Duration.ofSeconds(30)).corpusFiles());
  }

  @Test
  void jsonIsStableAndEscapesText() throws Exception {
    Files.writeString(dir.resolve("quote\".xlsx".replace("\"", "'")), "not a workbook");
    CorpusResult r = CorpusLoad.run(List.of(dir), Duration.ofSeconds(30));
    String json = r.toJson("2026-11-04");
    assertTrue(json.contains("\"schema\": 1"), json);
    assertTrue(json.contains("\"date\": \"2026-11-04\""), json);
    assertTrue(json.contains("\"corpusFiles\": 1"), json);
    assertTrue(json.contains("\"crashed\": 0"), json);
    assertEquals(json, r.toJson("2026-11-04"));
  }

  @Test
  void jsonEscapesControlCharactersAndQuotes() {
    CorpusResult r =
        new CorpusResult(
            1,
            0,
            java.util.Map.of(),
            1,
            0,
            0,
            0,
            0,
            0,
            java.util.Map.of(),
            List.of(new CorpusResult.Failure("a\"b.xlsx", "CRASH", "line1\nline2\t\\")),
            new ParseStats());
    String json = r.toJson("2026-11-04");
    assertTrue(json.contains("a\\\"b.xlsx"), json);
    assertTrue(json.contains("line1\\nline2\\t\\\\"), json);
  }
}
