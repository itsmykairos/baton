// Optional OpenTelemetry tracing for Baton runs. Depends on baton-api only;
// add it next to baton-core and register OpenTelemetryPipelineListener.
plugins {
    `java-library`
}

java {
    withJavadocJar()
}

dependencies {
    api(project(":baton-api"))
    api(libs.opentelemetry.api)

    testImplementation(project(":baton-testkit"))
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly(project(":baton-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "dev.baton.opentelemetry")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}
