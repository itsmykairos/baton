pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "baton"

include(
    "baton-bom",
    "baton-api",
    "baton-core",
    "baton-testkit",
    "baton-micrometer",
    "baton-opentelemetry",
    "baton-visualize",
    "baton-slf4j",
    "baton-spring-boot-starter",
    "baton-integration-tests",
    "baton-benchmarks",
)

// Every folder under examples/ with a build file is a runnable example, :examples:<folder>.
// Adding an example needs no change here.
file("examples")
    .listFiles { dir -> dir.resolve("build.gradle.kts").isFile }
    .orEmpty()
    .sortedBy { it.name }
    .forEach { include(":examples:${it.name}") }
