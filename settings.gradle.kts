rootProject.name = "cellerity"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

include(
    "cellerity-io",
    "cellerity-formula",
    "cellerity-core",
    "cellerity-interp",
    "cellerity-truffle",
    "cellerity-graph",
    "cellerity-api",
    "cellerity-cli",
    "cellerity-report",
    "cellerity-bench",
    "cellerity-eval",
)
project(":cellerity-eval").projectDir = file("eval")
