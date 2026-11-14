// A runnable Spring Boot example of the Baton starter. Not published.
//   ./gradlew :baton-examples-spring:run
plugins {
    application
}

dependencies {
    // An app's usual Boot starter (YAML config, Logback), plus Baton's.
    implementation(libs.spring.boot.starter)
    implementation(project(":baton-spring-boot-starter"))
}

tasks.withType<JavaCompile>().configureEach {
    // Lets Spring match bean method parameters by name, as Boot's own plugin does.
    options.compilerArgs.add("-parameters")
}

application {
    mainClass = "dev.baton.examples.spring.SpringExample"
}
