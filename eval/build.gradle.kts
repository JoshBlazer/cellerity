plugins {
    id("cellerity.java-conventions")
    application
}

dependencies {
    implementation(project(":cellerity-core"))
    implementation(project(":cellerity-io"))
    implementation(project(":cellerity-formula"))
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
