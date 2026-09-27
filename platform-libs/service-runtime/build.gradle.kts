// service-runtime/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6; INV-T-2860 /
// T16 scaffold; INV-T-2871 / T27 gives it its real interfaces).
//
// service-runtime is first in STK-60's canonical L1 order (`L1_MODULE_ORDER`): it
// uses no other L1 module (`ArchitectureTest`, this module's own §18.9 check) and
// is itself the composition root every service's bootstrap imports alongside the
// other L1 modules it embeds (TPL-18). This train adds: Spring Boot 4.1
// bootstrap auto-configuration; the closed set of SDS §12.1 binding interfaces
// (`binding/Binding.kt`) with the `SecretStore` binding's one shipped
// implementation (STK-29); liveness/readiness with a `DEGRADED` status distinct
// from `DOWN` (FR-AH.17); the uniform diagnostic endpoint and per-code error
// counter (FR-AE.82, FR-O.8, INV-AC-2172); and the MDC allow-list plus W3C trace
// context primitives for ECS structured logging (STK-34, STK-35, CS-34).
//
// One Gradle project, one version, one changelog for both halves of its api/impl
// split:
//   - `src/main` (published as `service-runtime-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on.
//   - `src/impl` (published as `service-runtime`) — the Spring Boot auto-configuration
//     and the shipped binding/health/diagnostics/logging implementations; depended
//     on only through a service's own `bootstrap` module (TPL-01, TPL-18), never by
//     another L1/L2 module.
// `src/test` tests the api half only; `src/implTest` tests the impl half and runs
// against both halves' output (STK-37/STK-38/STK-41, TPL-14).

import gst.build.Layer

plugins {
    id("gst.library")
    `maven-publish`
}

gstLibrary {
    layer = Layer.L1
}

version = file("version.txt").readText().trim()

sourceSets {
    // `resources.srcDir("src/impl/resources")` is deliberately not repeated here:
    // the Kotlin/Java plugins already default a newly created source set's own
    // resources directory to `src/<name>/resources`, and this module is the
    // first Wave 1 scaffold whose `impl` half actually ships a resource
    // (`service-runtime-defaults.properties`, `AutoConfiguration.imports`) —
    // the sibling modules' identical explicit `resources.srcDir(...)` call was
    // dead weight until now; adding it back here would register the same
    // directory twice and fail `processImplResources` with "duplicate" (found
    // running this task's own build; out of this task's declared scope to fix
    // in the sibling modules, which have nothing under `impl/resources` yet
    // for it to actually break).
    create("impl") {
        kotlin.srcDir("src/impl/kotlin")
    }
    create("implTest") {
        kotlin.srcDir("src/implTest/kotlin")
    }
}

dependencies {
    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`, e.g. `testImplementation(sourceSets.main.get().output)`).
    add("implImplementation", sourceSets["main"].output)
    add("implTestImplementation", sourceSets["main"].output)
    add("implTestImplementation", sourceSets["impl"].output)

    // STK-05: Spring Boot 4.1 for the impl half only (ARC-16: the api half stays
    // framework-free). One BOM entry, no separately pinned Spring/Micrometer/
    // Logback versions. implTest needs it too: it depends on impl's compiled
    // *output* only (source-set output is not transitive over impl's own
    // dependencies).
    listOf("implImplementation", "implTestImplementation").forEach { config ->
        add(config, platform(libs.springBoot.dependencies))
        add(config, libs.springBoot.starterActuator)
    }
    add("implTestImplementation", libs.springBoot.test)
    add("implTestImplementation", libs.assertj.core)

    // service-runtime hosts the repository-wide §18.9 L1-dependency-order Konsist
    // check (done-when, INV-T-2860 / T16): one module needs to, and this is the
    // composition root every other L1 module is wired through. Against a fixture,
    // not this repository's own sources — see L1DependencyOrderTest's KDoc. Its own
    // `ArchitectureTest` (STK-41, the adapter-runtime pattern) checks this module's
    // real sources import no other L1 module.
    listOf("testImplementation", "implTestImplementation").forEach { config ->
        add(config, platform(libs.junit.bom))
        add(config, libs.junit.jupiter)
        add(config, libs.kotest.runnerJunit5)
        add(config, libs.kotest.assertionsCore)
        add(config, libs.kotest.property)
        add(config, libs.mockk)
        add(config, libs.kotlinx.coroutinesTest)
        add("${config.removeSuffix("Implementation")}RuntimeOnly", libs.junit.platformLauncher)
    }
    testImplementation(libs.platform.konsistRules)
    add("implTestImplementation", libs.platform.konsistRules)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, matched with platform-build's own modules
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
}

val implTestTask =
    tasks.register<Test>("implTest") {
        group = "verification"
        description = "Runs the impl half's tests: bindings, health, diagnostics and logging."
        testClassesDirs = sourceSets["implTest"].output.classesDirs
        classpath = sourceSets["implTest"].runtimeClasspath
        useJUnitPlatform()
        jvmArgs("-Duser.timezone=UTC")
        systemProperty("user.language", "de")
        systemProperty("user.country", "DE")
    }

tasks.named("check") {
    dependsOn(implTestTask)
}

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        from(sourceSets["impl"].output)
    }

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "${project.name}-api"
            from(components["java"]) // `main` only
        }
        create<MavenPublication>("impl") {
            artifactId = project.name
            artifact(implJar)
        }
    }
}

// Two Jar tasks publish from this one project (`jar` for `api`, `implJar` for
// `impl`); every publish-related task's own validation otherwise flags an
// undeclared dependency on whichever Jar task is not its own (Gradle scans all
// of a project's Jar tasks when generating a publication's module metadata).
tasks.matching { it.name.startsWith("generateMetadataFileFor") || it.name.startsWith("publish") }.configureEach {
    dependsOn(implJar, tasks.named("jar"))
}
