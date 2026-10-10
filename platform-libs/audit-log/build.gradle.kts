// audit-log/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6; INV-T-2860 / T16 scaffold,
// INV-T-2868 / T24 real interface).
//
// The audit-entry writer and the override path (INV-REQ-1180, -1179, -1226, -2588). "Order within
// L1: uses outbox-and-queue where the service has a data-plane schema" (Keel INV-MOD-2739):
// the entry is written through outbox-and-queue's `OutboxWriter` in the command's transaction; the
// override grant table carries `tenant_id` and tenancy-kernel's forced row-level security. Wired
// as outbox-and-queue wires tenancy-kernel (see its build script for the `implJar` reasoning).
//
// One Gradle project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `audit-log-api`) — ports and value types only, framework-free.
//   - `src/impl` (published as `audit-log`) — the JDBC writer, override service and grant table.

import gst.build.Layer

plugins {
    id("gst.library")
    id("java-library") // `api(...)`: tenancy-kernel and outbox-and-queue types are in this module's public signatures
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

// Project evaluation order is alphabetical, so these modules' `implJar` tasks would not exist yet.
evaluationDependsOn(":tenancy-kernel")
evaluationDependsOn(":outbox-and-queue")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")
val outboxImplJar = project(":outbox-and-queue").tasks.named("implJar")

dependencies {
    api(project(":tenancy-kernel"))
    api(project(":outbox-and-queue"))
    add("implImplementation", project(":tenancy-kernel"))
    add("implImplementation", project(":outbox-and-queue"))
    add("implImplementation", files(tenancyKernelImplJar))
    add("implImplementation", files(outboxImplJar))

    // STK-08: Jackson 3 (`tools.jackson`) — the AuditEntryRecorded payload.
    add("implImplementation", libs.jackson.databind)

    // Tests run the real outbox over PostgreSQL 17/18 (STK-14, STK-15) with outbox-and-queue's fixtures.
    testImplementation(sourceSets["impl"].output)
    testImplementation(files(tenancyKernelImplJar))
    testImplementation(files(outboxImplJar))
    testImplementation(testFixtures(project(":outbox-and-queue")))
    testImplementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)

    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`, e.g. `testImplementation(sourceSets.main.get().output)`).
    add("implImplementation", sourceSets["main"].output)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, as outbox-and-queue
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
}

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        destinationDirectory.set(layout.buildDirectory.dir("libs-impl")) // as outbox-and-queue: no clash with `jar`
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
