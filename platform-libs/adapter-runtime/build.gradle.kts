// adapter-runtime/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6; INV-T-2860 /
// T16 scaffold; INV-T-2862 / T18 gives it its real interfaces).
//
// One Gradle project, one version, one changelog for both halves of its api/impl
// split:
//   - `src/main` (published as `adapter-runtime-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on.
//   - `src/impl` (published as `adapter-runtime`) — the implementation, built on top
//     of `src/main`'s output; depended on only through `service-runtime`'s
//     composition root or `product-runtime` (TPL-01, TPL-18), never by another
//     L1/L2 module.
// `src/test` tests the api half only; `src/implTest` tests the impl half and runs
// against both halves' output, the same relationship `src/impl` itself has to
// `src/main` (STK-37/STK-38/STK-41: JUnit + Kotest + MockK + Konsist, TPL-14).

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
    create("impl") {
        kotlin.srcDir("src/impl/kotlin")
        resources.srcDir("src/impl/resources")
    }
    create("implTest") {
        kotlin.srcDir("src/implTest/kotlin")
        resources.srcDir("src/implTest/resources")
    }
}

dependencies {
    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`, e.g. `testImplementation(sourceSets.main.get().output)`).
    add("implImplementation", sourceSets["main"].output)
    add("implTestImplementation", sourceSets["main"].output)
    add("implTestImplementation", sourceSets["impl"].output)

    // STK-24: Resilience4j core for the impl half only (ARC-16: the api half stays
    // framework-free). One BOM entry, no separately pinned Resilience4j versions.
    // implTest needs it too: it depends on impl's compiled *output* only
    // (source-set output is not transitive over impl's own dependencies).
    listOf("implImplementation", "implTestImplementation").forEach { config ->
        add(config, platform(libs.resilience4j.bom))
        add(config, libs.resilience4j.circuitbreaker)
        add(config, libs.resilience4j.retry)
        add(config, libs.resilience4j.bulkhead)
        add(config, libs.resilience4j.timelimiter)
    }

    // STK-37/STK-38/STK-41: JUnit Jupiter + Kotest assertions/property + MockK +
    // Konsist, for both the api half's own tests and the impl half's.
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
    testImplementation(libs.platform.konsistRules) // the architecture test (TPL-14) only
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val implTestTask =
    tasks.register<Test>("implTest") {
        group = "verification"
        description =
            "Runs the impl half's tests: the Resilience4j executor, the cutover router, the SecretStore port contract."
        testClassesDirs = sourceSets["implTest"].output.classesDirs
        classpath = sourceSets["implTest"].runtimeClasspath
        useJUnitPlatform()
    }

tasks.named("check") {
    dependsOn(implTestTask)
}

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        // A separate local output directory, as tenancy-kernel's `implJar` (not a classifier, which would rename
        // the published `impl` artifact): the api `jar` writes the same file name under `build/libs/`, so without
        // this whichever of the two ran last overwrote the other on disk.
        destinationDirectory.set(layout.buildDirectory.dir("libs-impl"))
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
