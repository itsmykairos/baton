// Optional SLF4J integration: carries the MDC into stage threads. Depends on baton-api only.
plugins {
    `java-library`
}

java {
    withJavadocJar()
}

dependencies {
    api(project(":baton-api"))
    api(libs.slf4j.api)

    testRuntimeOnly(project(":baton-core"))
    // Logback has a real MDC; slf4j-simple's MDC stores nothing.
    testRuntimeOnly(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "dev.baton.slf4j")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}
