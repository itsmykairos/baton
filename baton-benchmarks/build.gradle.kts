// JMH benchmarks of engine overhead. Not published.
//
//   ./gradlew :baton-benchmarks:jmh                          # all benchmarks
//   ./gradlew :baton-benchmarks:jmh -Pjmh="Sequential -prof gc"  # filter + JMH options
plugins {
    java
}

dependencies {
    implementation(project(":baton-api"))
    runtimeOnly(project(":baton-core"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.annprocess)
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the JMH benchmarks; pass JMH arguments with -Pjmh=\"...\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
    args((providers.gradleProperty("jmh").orNull ?: "").split(" ").filter { it.isNotBlank() })
    // Benchmarks are never up to date.
    outputs.upToDateWhen { false }
}
