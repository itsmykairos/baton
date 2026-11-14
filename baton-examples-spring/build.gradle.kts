// A runnable Spring Boot web app using the Baton starter. Not published.
//   ./gradlew :baton-examples-spring:run   then open http://localhost:8080
plugins {
    application
}

dependencies {
    // An app's usual Boot starters (web, actuator), plus Baton's.
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(project(":baton-spring-boot-starter"))
    implementation(project(":baton-visualize"))
}

tasks.withType<JavaCompile>().configureEach {
    // Lets Spring match bean method parameters by name, as Boot's own plugin does.
    options.compilerArgs.add("-parameters")
}

application {
    mainClass = "dev.baton.examples.spring.SpringExample"
}
