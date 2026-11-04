plugins {
    id("cellerity.java-conventions")
    application
}

dependencies {
    implementation(project(":cellerity-core"))
    implementation(project(":cellerity-io"))
}

application {
    applicationName = "cellerity"
    mainClass = "${project.group}.cellerity.cli.Main"
}
