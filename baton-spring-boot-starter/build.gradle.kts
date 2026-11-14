// Spring Boot 4 starter: BatonConfig from application.yml, auto-wired integrations,
// YAML-declared pipelines and an /actuator/baton endpoint.
plugins {
    `java-library`
}

java {
    withJavadocJar()
}

dependencies {
    api(project(":baton-api"))
    api(project(":baton-slf4j"))
    api(libs.spring.boot.autoconfigure)
    implementation(project(":baton-visualize"))
    runtimeOnly(project(":baton-core"))

    // Optional integrations: active when the app adds them.
    compileOnly(project(":baton-micrometer"))
    compileOnly(project(":baton-opentelemetry"))
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.actuator.autoconfigure)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(project(":baton-micrometer"))
    testImplementation(project(":baton-opentelemetry"))
    testImplementation(project(":baton-testkit"))
    testImplementation(libs.spring.boot.actuator)
    testImplementation(libs.spring.boot.actuator.autoconfigure)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // Actuator maps @Selector parameters (/actuator/baton/{bean}) by their names.
    options.compilerArgs.add("-parameters")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "dev.baton.spring.boot")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}
