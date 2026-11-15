plugins {
    application
}

dependencies {
    // Compile against the API only; the runtime is bound through ServiceLoader.
    implementation(project(":baton-api"))
    implementation(project(":baton-visualize"))
    runtimeOnly(project(":baton-core"))
}

application {
    mainModule = "dev.baton.examples"
    mainClass = "dev.baton.examples.BatonExample"
}
