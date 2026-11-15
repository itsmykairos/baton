// The cookbook's recipes, as tests. The docs site includes their code between
// "--8<-- [start:name]" and "--8<-- [end:name]" markers, so every snippet on the
// site compiles and runs. Not published.
plugins {
    java
}

dependencies {
    testImplementation(project(":baton-api"))
    testImplementation(project(":baton-testkit"))
    testImplementation(project(":baton-visualize"))
    testImplementation(project(":baton-slf4j"))
    testRuntimeOnly(project(":baton-core"))
    // Logback has a real MDC; slf4j-simple's MDC stores nothing.
    testRuntimeOnly(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
