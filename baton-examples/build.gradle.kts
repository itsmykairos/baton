plugins {
    application
}

dependencies {
    implementation(project(":baton-api"))
    implementation(project(":baton-core"))
}

application {
    mainClass = "dev.baton.examples.BatonExample"
}
