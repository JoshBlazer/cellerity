plugins {
    id("cellerity.java-conventions")
    application
}

dependencies {
    implementation(project(":cellerity-core"))
    implementation(project(":cellerity-io"))
}

application {
    mainClass = "${project.group}.cellerity.eval.CorpusLoad"
}

// Run from the repository root so corpus and result paths in arguments are stable.
tasks.named<JavaExec>("run") { workingDir = rootDir }
