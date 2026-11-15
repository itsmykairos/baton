// Black-box tests of the public API. Tests compile against baton-api and
// baton-testkit only; baton-core is on the runtime classpath and is found
// through ServiceLoader, exactly as an application would use it.
plugins {
    java
}

dependencies {
    testImplementation(project(":baton-api"))
    testImplementation(project(":baton-testkit"))
    testRuntimeOnly(project(":baton-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
