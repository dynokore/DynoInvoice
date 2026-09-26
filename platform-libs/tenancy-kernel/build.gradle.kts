// tenancy-kernel/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6;
// INV-T-2860 / T16 scaffold, INV-T-2861 / T17 real interface).
//
// Tenant context per request and message, RLS with the session tenant set per
// transaction and cleared on return to the pool, the mandatory `tenant_id`
// migration helper, the LicenceCheck port (CS-19, ARC-21, FR-G.5, FR-G.12).
// Tenancy-kernel uses no other L1 module (Keel INV-ADR-2774) — no
// `project(":...")` dependency below, by design, not an oversight.
//
// One Gradle project, one version, one changelog for both halves of its
// api/impl split:
//   - `src/main` (published as `tenancy-kernel-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on.
//   - `src/impl` (published as `tenancy-kernel`) — the implementation, built on top
//     of `src/main`'s output; depended on only through `service-runtime`'s
//     composition root or `product-runtime` (TPL-01, TPL-18), never by another
//     L1/L2 module.
//   - `src/testFixtures` (`java-test-fixtures`) — the isolation-test fixtures
//     done-when item (§9.2, §13.3): a real PostgreSQL Testcontainers fixture and
//     the tenant-probe helpers (TPL-14), published so a service's own RLS
//     tests can depend on `testFixtures(libs.platform.tenancyKernel)` instead
//     of rebuilding this scaffolding per service.

import gst.build.Layer

plugins {
    id("gst.library")
    id("java-test-fixtures")
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

    // `test` and `testFixtures` exercise the impl half directly (Testcontainers
    // RLS proof, the consumer contract test) — not only through `-api`.
    testImplementation(sourceSets["impl"].output)
    add("testFixturesImplementation", sourceSets["impl"].output)

    // Real PostgreSQL only, in every environment including tests (STK-14,
    // STK-15; H2 is banned outright, banned-dependencies.yaml). `testFixturesApi`,
    // not `-Implementation`: a consuming service's own `testImplementation
    // testFixtures(libs.platform.tenancyKernel)` needs these transitively to
    // actually run the fixture, not just to compile against it.
    testFixturesApi(libs.postgresql)
    testFixturesApi(libs.testcontainers.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple) // Testcontainers' own container/strategy logs are otherwise silently dropped (NOP logger)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, matched with platform-build's own modules
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
    // Testcontainers needs the host Docker socket (CODER-BRIEF: "the CI runner has the host Docker socket").
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
