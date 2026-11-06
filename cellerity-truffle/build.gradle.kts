plugins { id("cellerity.java-conventions") }

dependencies {
    api(project(":cellerity-interp"))
    implementation(libs.truffle.api)
    // Finds the optimizing runtime on a GraalVM JDK, and falls back without one (ADR-0006, ADR-0018).
    runtimeOnly(libs.truffle.runtime)
}

// The differential reuses the interpreter's workbook builder rather than copy it.
sourceSets.test {
    java.srcDir("../cellerity-interp/src/test/java")
    java.include("**/TestBooks.java", "**/TruffleDifferentialTest.java")
}
