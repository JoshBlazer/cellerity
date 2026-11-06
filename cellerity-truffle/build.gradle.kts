plugins { id("cellerity.java-conventions") }

dependencies {
    api(project(":cellerity-interp"))
    implementation(libs.truffle.api)
    // Finds the optimizing runtime on a GraalVM JDK, and falls back without one (ADR-0006, ADR-0018).
    runtimeOnly(libs.truffle.runtime)
}

// Evidence for ADR-0006: which runtime this JDK gives, and whether formulas compile.
tasks.register<Sync>("probeLibs") {
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("probe/lib"))
}

// The differential reuses the interpreter's workbook builder, so the two tests cannot drift apart.
val testBooks = tasks.register<Sync>("testBooks") {
    from("../cellerity-interp/src/test/java") { include("**/TestBooks.java") }
    into(layout.buildDirectory.dir("generated/testBooks"))
}
sourceSets.test { java.srcDir(testBooks) }

spotless { java { target("src/**/*.java") } }
