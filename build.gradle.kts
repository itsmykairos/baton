import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    base
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.axion.release)
}

// group comes from gradle.properties. The version comes from git tags (axion-release):
// exactly 0.1.0-RC1 on the commit tagged v0.1.0-RC1, and a -SNAPSHOT between tags
// (0.1.0-SNAPSHOT before the first tag; on other branches with the branch name added).
// The Release workflow creates the tag. ./gradlew cV prints the current version.
scmVersion {
    // After v0.1.0-RC1 snapshots read 0.1.0-RC2-SNAPSHOT (the default would say
    // 0.1.1-SNAPSHOT); after a final release, the next patch.
    versionIncrementer("incrementPrerelease")
}
version = scmVersion.version

/** Published modules and their POM descriptions. */
val published = mapOf(
    "baton-bom" to "Bill of materials aligning the versions of every Baton module.",
    "baton-api" to "Baton public API: pipelines, stages, context, results, events and configuration.",
    "baton-core" to "Baton runtime: runs pipelines of stages on virtual threads.",
    "baton-testkit" to "Helpers for testing Baton stages and pipelines.",
    "baton-micrometer" to "Micrometer metrics for Baton pipeline runs.",
    "baton-opentelemetry" to "OpenTelemetry tracing and context propagation for Baton pipeline runs.",
    "baton-slf4j" to "Carries the SLF4J MDC into Baton stage threads.",
    "baton-visualize" to "Mermaid diagrams of Baton pipelines and runs.",
    "baton-spring-boot-starter" to "Spring Boot auto-configuration for Baton.",
)

val palantirJavaFormatVersion = libs.versions.palantir.java.format.get()

// Build scripts, workflows and config: whitespace only.
spotless {
    format("misc") {
        target("**/*.gradle.kts", "gradle/*.toml", ".github/**/*.yml", ".gitignore", "*.properties")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

/** Published modules whose public API is recorded in api/<module>.api (core exports none). */
val apiChecked = published.keys - setOf("baton-bom", "baton-core")

subprojects {
    group = rootProject.group
    version = rootProject.version

    // Every Java source set is formatted with palantir-java-format; `check` runs spotlessCheck.
    plugins.withId("java") {
        apply(plugin = "com.diffplug.spotless")
        extensions.configure<SpotlessExtension> {
            java {
                target("src/**/*.java")
                palantirJavaFormat(palantirJavaFormatVersion).formatJavadoc(false)
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    val pomDescription = published[name]
    if (pomDescription != null) {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            // Signs only when a key is provided (CI release); local publishing works without one.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            coordinates(rootProject.group.toString(), project.name, rootProject.version.toString())
            if (project.name == "baton-core") {
                // Core exports no API; Central only requires a Javadoc jar to exist.
                plugins.withId("java-library") {
                    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = true))
                }
            }
            pom {
                name.set(project.name)
                description.set(pomDescription)
                inceptionYear.set("2026")
                url.set("https://github.com/kairos-tools/baton")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("itsmykairos")
                        name.set("itsmykairos")
                        url.set("https://github.com/itsmykairos")
                    }
                }
                scm {
                    url.set("https://github.com/kairos-tools/baton")
                    connection.set("scm:git:https://github.com/kairos-tools/baton.git")
                    developerConnection.set("scm:git:ssh://git@github.com/kairos-tools/baton.git")
                }
            }
        }
    }

    if (name in apiChecked) {
        plugins.withId("java") {
            val javaToolchains = extensions.getByType<JavaToolchainService>()
            val javap = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                .map { it.metadata.installationPath.file("bin/javap").asFile.absolutePath }
            val classes = extensions.getByType<SourceSetContainer>()["main"].output.classesDirs
            val apiFile = layout.projectDirectory.file("api/$name.api")

            tasks.register<ApiDump>("apiDump") {
                group = "verification"
                description = "Records this module's public API in api/${project.name}.api."
                this.classes.from(classes)
                this.javap.set(javap)
                this.apiFile.set(apiFile)
            }
            val apiCheck = tasks.register<ApiCheck>("apiCheck") {
                group = "verification"
                description = "Fails if this module's public API differs from api/${project.name}.api."
                this.classes.from(classes)
                this.javap.set(javap)
                this.apiFile.from(apiFile)
                this.module.set(project.name)
            }
            tasks.named("check") { dependsOn(apiCheck) }
        }
    }
}

// The Javadoc of every module with a recorded API, for the docs site (mkdocs.yml):
// build/docs-api/<module>/, copied under api/ after mkdocs builds the site.
val docsJavadoc = tasks.register<Sync>("docsJavadoc") {
    group = "documentation"
    description = "Collects each published module's Javadoc into build/docs-api for the docs site."
    into(layout.buildDirectory.dir("docs-api"))
}
apiChecked.forEach { module ->
    docsJavadoc.configure {
        from(project(":$module").tasks.named("javadoc")) { into(module) }
    }
}

/**
 * Renders a module's public API: every public class in its compiled output, with its
 * public and protected members, as printed by `javap -public`, sorted for stability.
 */
abstract class PublicApiTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:Input
    abstract val javap: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    protected fun render(): String {
        val dirs = classes.files.filter { it.isDirectory }
        val names = dirs.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .filter { it.name != "module-info.class" && it.name != "package-info.class" }
                .map { it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                .toList()
        }.sorted()
        if (names.isEmpty()) {
            return ""
        }
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine(listOf(javap.get(), "-public", "-cp", dirs.joinToString(File.pathSeparator)) + names)
            standardOutput = output
        }
        val blocks = mutableListOf<String>()
        var current = StringBuilder()
        for (line in output.toString(Charsets.UTF_8).lines()) {
            if (line.startsWith("Compiled from")) {
                continue
            }
            current.append(line).append('\n')
            if (line == "}") {
                blocks += current.toString()
                current = StringBuilder()
            }
        }
        // javap prints non-public classes too (with no members); they are not API.
        return blocks
            .filter { it.lineSequence().first().startsWith("public ") }
            .sortedBy { it.lineSequence().first() }
            .joinToString("\n")
    }
}

/** Writes the module's public API to its api file; run it after an intended API change. */
abstract class ApiDump : PublicApiTask() {

    @get:OutputFile
    abstract val apiFile: RegularFileProperty

    @TaskAction
    fun dump() {
        apiFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(render())
        }
    }
}

/** Fails when the module's public API no longer matches its committed api file. */
abstract class ApiCheck : PublicApiTask() {

    /** The committed api file; a collection so a missing file is reported, not a build error. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiFile: ConfigurableFileCollection

    @get:Input
    abstract val module: Property<String>

    @TaskAction
    fun check() {
        val file = apiFile.singleFile
        if (!file.exists()) {
            throw GradleException("No recorded API for ${module.get()}: run ./gradlew apiDump and commit ${file.name}")
        }
        val expected = file.readText()
        val actual = render()
        if (expected != actual) {
            val before = expected.lines().toSet()
            val after = actual.lines().toSet()
            val removed = (before - after).filter { it.isNotBlank() }.map { "- $it" }
            val added = (after - before).filter { it.isNotBlank() }.map { "+ $it" }
            throw GradleException(
                "Public API of ${module.get()} changed:\n" +
                    (removed + added).take(40).joinToString("\n") +
                    "\nIf this is intended, run ./gradlew apiDump and commit api/${module.get()}.api."
            )
        }
    }
}
