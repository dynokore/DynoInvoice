// service-runtime/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6; INV-T-2860 / T16).
//
// Scaffolding only (repository README.md: "design only; no code yet") — this
// train's job is the module's build shape, not its interfaces: those land with
// the Wave 1 service or module task that specifies them (TPL-14). One Gradle
// project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `service-runtime-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on.
//   - `src/impl` (published as `service-runtime`) — the implementation, built on top
//     of `src/main`'s output; depended on only through `service-runtime`'s
//     composition root or `product-runtime` (TPL-01, TPL-18), never by another
//     L1/L2 module.

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
}

dependencies {
    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`, e.g. `testImplementation(sourceSets.main.get().output)`).
    add("implImplementation", sourceSets["main"].output)

    // service-runtime hosts the repository-wide §18.9 L1-dependency-order Konsist
    // check (done-when, INV-T-2860 / T16): one module needs to, and this is the
    // composition root every other L1 module is wired through. Against a fixture,
    // not this repository's own sources — see L1DependencyOrderTest's KDoc.
    testImplementation(libs.platform.konsistRules)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, matched with platform-build's own modules
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
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
