// Optional Micrometer metrics for Baton runs. Depends on baton-api only;
// add it next to baton-core and register MicrometerPipelineListener.
plugins {
    `java-library`
}


dependencies {
    api(project(":baton-api"))
    api(libs.micrometer.core)

    testImplementation(project(":baton-testkit"))
    testRuntimeOnly(project(":baton-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "dev.baton.micrometer")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}
