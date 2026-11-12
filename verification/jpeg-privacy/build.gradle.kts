// SPDX-License-Identifier: Apache-2.0
// Independent JPEG output privacy checker (ST-042, PRIV-007, PRIV-021). Pure Kotlin/JVM: the only runtime
// dependency is the Kotlin standard library. It must never depend on the library's own JPEG or EXIF code
// (AC3); the task `verifyCheckerIndependence` fails the build if it ever does.
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    application
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
    testFixturesImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.shishark.faceblur.verification.jpeg.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Deterministic: one JVM, tests in a fixed order is not required; no randomness without a printed seed.
    maxParallelForks = 1
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

ktlint {
    version.set(libs.versions.ktlint.engine.get())
}

kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

// AC3: the checker has no dependency on the library or on any other JPEG/EXIF code, and needs no external tool.
abstract class CheckerIndependenceTask : DefaultTask() {
    @get:Input abstract val runtimeModules: ListProperty<String>

    @get:Input abstract val testRuntimeModules: ListProperty<String>

    @get:Input abstract val allowedModules: SetProperty<String>

    /** The module itself (its test fixtures are a project component on the test classpath). */
    @get:Input abstract val self: Property<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val runtime = runtimeModules.get().toSortedSet()
        val test = testRuntimeModules.get().toSortedSet()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText("runtimeClasspath: ${runtime.joinToString()}\ntestRuntimeClasspath: ${test.joinToString()}\n")
        }
        val unexpected = runtime.filter { it !in allowedModules.get() }
        check(unexpected.isEmpty()) { "Unexpected runtime dependencies of the checker: $unexpected" }
        // Project components, included builds and file dependencies are invisible to a coordinate check: refuse them.
        val foreign = (runtime + test).filter {
            (it.startsWith("project:") && it != self.get()) || it == "file-dependency"
        }
        check(foreign.isEmpty()) { "The checker must not depend on other projects, included builds or files: $foreign" }
        val library = (runtime + test).filter { it.startsWith("io.github.shishark") }
        check(library.isEmpty()) { "The checker must not depend on the library: $library" }
    }
}

fun modulesOf(configuration: Configuration): Provider<List<String>> {
    val files = configuration.allDependencies.filterIsInstance<FileCollectionDependency>().map { "file-dependency" }
    return configuration.incoming.resolutionResult.rootComponent.map { root ->
        val seen = linkedSetOf<ResolvedComponentResult>()
        fun walk(component: ResolvedComponentResult) {
            if (!seen.add(component)) return
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root)
        files + seen.mapNotNull {
            when (val id = it.id) {
                is ModuleComponentIdentifier -> "${id.group}:${id.module}"

                is ProjectComponentIdentifier -> if (it ===
                    root
                ) {
                    null
                } else {
                    "project:${id.build.buildPath}${id.projectPath}"
                }

                else -> null
            }
        }
    }
}

val verifyCheckerIndependence = tasks.register<CheckerIndependenceTask>("verifyCheckerIndependence") {
    group = "verification"
    description = "Fails if the checker's runtime classpath has anything but the Kotlin standard library."
    runtimeModules.set(modulesOf(configurations.runtimeClasspath.get()))
    testRuntimeModules.set(modulesOf(configurations.testRuntimeClasspath.get()))
    allowedModules.set(setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations"))
    self.set("project:${project.gradle.parent?.rootProject?.path ?: ":"}${project.path}")
    report.set(layout.buildDirectory.file("reports/independence/dependencies.txt"))
}

tasks.check { dependsOn(verifyCheckerIndependence, tasks.named("koverVerify")) }
