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
    "baton-examples",
    "baton-examples-spring"
)
