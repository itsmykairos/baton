// Optional diagrams of pipelines and runs. Depends on baton-api only.
plugins {
    `java-library`
}

java {
    withJavadocJar()
}

dependencies {
    api(project(":baton-api"))

    testRuntimeOnly(project(":baton-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}
