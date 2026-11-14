// Bill of materials: import it to align the versions of every Baton module.
plugins {
    `java-platform`
}

dependencies {
    constraints {
        api(project(":baton-api"))
        api(project(":baton-core"))
        api(project(":baton-testkit"))
        api(project(":baton-micrometer"))
        api(project(":baton-opentelemetry"))
        api(project(":baton-slf4j"))
        api(project(":baton-visualize"))
        api(project(":baton-spring-boot-starter"))
    }
}
