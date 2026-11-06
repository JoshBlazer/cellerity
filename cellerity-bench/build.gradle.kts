plugins { id("cellerity.java-conventions") }

dependencies {
    implementation(project(":cellerity-interp"))
    implementation(project(":cellerity-truffle"))
    // Only as what the benchmark compares with (ADR-0018). This module is never published.
    implementation(libs.poi.ooxml)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.processor)
}

// JMH generates code that does not meet this project's warning rules.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.remove("-Werror")
    options.compilerArgs.remove("-Xlint:all,-processing,-serial")
}

// Everything needed to run on a JDK other than the build's: java -cp 'lib/*' org.openjdk.jmh.Main
tasks.register<Sync>("benchLibs") {
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("bench/lib"))
}
