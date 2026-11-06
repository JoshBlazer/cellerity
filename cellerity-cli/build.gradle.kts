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
}

application {
    applicationName = "cellerity"
    mainClass = "${project.group}.cellerity.cli.Main"
}
