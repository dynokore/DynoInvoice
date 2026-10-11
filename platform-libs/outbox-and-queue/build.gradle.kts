// outbox-and-queue/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6;
// INV-T-2860 / T16 scaffold, INV-T-2864 / T20 real interface).
//
// Outbox, common event envelope, relay, inbox and per-aggregate ordering (STK-18,
// INV-REQ-815, -1162, -1164, -1205, -1211, -1212, -1221, -1222). "Order within L1: uses
// tenancy-kernel" (Keel INV-MOD-2372): tenant context, `TransactionRunner`, the mandatory
// `tenant_id` and row-level security come from tenancy-kernel, wired the same way
// evidence-store wires it (see that module's build script for the reasoning on the
// `implJar` dependency and `evaluationDependsOn`).
//
// One Gradle project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `outbox-and-queue-api`) — ports and value types only,
//     framework-free (ARC-16); depends on tenancy-kernel's `-api` half (`TenantId`).
//   - `src/impl` (published as `outbox-and-queue`) — the JDBC outbox writer, relay,
//     PostgreSQL event-log binding and inbox consumer; depended on only through
//     `service-runtime`'s composition root or `product-runtime` (TPL-01, TPL-18).
//   - `src/testFixtures` (`java-test-fixtures`) — a Testcontainers PostgreSQL fixture with
//     an event-log schema, a flaky event-log DataSource and a killable transaction runner.

import gst.build.Layer

plugins {
    id("gst.library")
    id("java-library") // `api(...)`: TenantId is in this module's public signatures
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

// Project evaluation order is alphabetical, so tenancy-kernel's `implJar` task would not exist yet.
evaluationDependsOn(":tenancy-kernel")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")

dependencies {
    api(project(":tenancy-kernel"))
    add("implImplementation", project(":tenancy-kernel"))
    add("implImplementation", files(tenancyKernelImplJar))
    testFixturesApi(files(tenancyKernelImplJar))
    testFixturesApi(testFixtures(project(":tenancy-kernel")))

    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`, e.g. `testImplementation(sourceSets.main.get().output)`).
    add("implImplementation", sourceSets["main"].output)
    add("testFixturesImplementation", sourceSets["impl"].output)
    testImplementation(sourceSets["impl"].output)

    // STK-08: Jackson 3 (`tools.jackson`) — the CloudEvents structured-JSON envelope.
    add("implImplementation", libs.jackson.databind)
    testImplementation(libs.jackson.databind)

    // Real PostgreSQL only, in every environment including tests (STK-14, STK-15;
    // H2 is banned outright, banned-dependencies.yaml).
    testFixturesApi(libs.postgresql)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutinesTest)
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
        // A separate local output directory, as tenancy-kernel's and evidence-store's `implJar`:
        // the default `jar` task's output would otherwise collide on disk (same name and version).
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
