plugins {
    id("cellerity.java-conventions")
    application
}

dependencies {
    implementation(project(":cellerity-core"))
    implementation(project(":cellerity-io"))
    implementation(project(":cellerity-formula"))
    implementation(project(":cellerity-graph"))
    implementation(project(":cellerity-interp"))
    implementation(project(":cellerity-report"))
    // Independent check of the reader only (ADR-0011). Never used by a published module.
    implementation(libs.poi.ooxml)
}

application {
    mainClass = "${project.group}.cellerity.eval.CorpusLoad"
}

// Run from the repository root so corpus and result paths in arguments are stable.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    maxHeapSize = "2g"
}

// Compares our calculated values with the values Excel stored.
tasks.register<JavaExec>("fidelity") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "${project.group}.cellerity.eval.Fidelity"
    workingDir = rootDir
    maxHeapSize = providers.gradleProperty("fidelityHeap").getOrElse("2g")
    args(providers.gradleProperty("fidelityArgs").orNull?.split(" ") ?: emptyList<String>())
}

// Compares incremental recalculation with a full one, on every corpus workbook.
tasks.register<JavaExec>("recalc") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "${project.group}.cellerity.eval.RecalcCheck"
    workingDir = rootDir
    maxHeapSize = providers.gradleProperty("fidelityHeap").getOrElse("2g")
    args(providers.gradleProperty("recalcArgs").orNull?.split(" ") ?: emptyList<String>())
}

// Compares what our reader stores with what Apache POI reads from the same files.
tasks.register<JavaExec>("crossCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "${project.group}.cellerity.eval.PoiCrossCheck"
    workingDir = rootDir
    maxHeapSize = "2g"
    args(providers.gradleProperty("crossCheckArgs").orNull?.split(" ") ?: emptyList<String>())
}

// A corpus tool, not part of the library (ADR-0011).
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
