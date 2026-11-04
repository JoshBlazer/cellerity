import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = versionCatalogs.named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

// Derived from the `group` property in gradle.properties (ADR-0001); never hard-code it here.
val javaModuleName = "${project.group}.cellerity.${project.name.removePrefix("cellerity-")}"

dependencies {
    errorprone(lib("errorprone-core"))
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("jqwik"))
    testRuntimeOnly(lib("junit-launcher"))
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = providers.gradleProperty("javaRelease").map(String::toInt)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
    options.errorprone.disableWarningsInGeneratedCode = true
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}

tasks.withType<Jar>().configureEach {
    manifest { attributes("Automatic-Module-Name" to javaModuleName) }
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        // The version Spotless pins and tests. Newer ones can break its glue code.
        googleJavaFormat()
        removeUnusedImports()
        formatAnnotations()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = "Cellerity: run Excel models as JIT-compiled Java services."
                url = "https://github.com/JoshBlazer/cellerity"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                scm { url = "https://github.com/JoshBlazer/cellerity" }
            }
        }
    }
}
