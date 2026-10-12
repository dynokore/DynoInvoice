// config-resolver/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6; INV-T-2860 / T16).
//
// INV-T-2869 / T25: precedence resolution and resolved-value recording; INV-T-2870 / T26: every
// write path (setting, history, control relaxation, audit entry and PolicyChanged). One Gradle
// project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `config-resolver-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on.
//   - `src/impl` (published as `config-resolver`) — the implementation, built on top
//     of `src/main`'s output; depended on only through `service-runtime`'s
//     composition root or `product-runtime` (TPL-01, TPL-18), never by another
//     L1/L2 module.

import gst.build.Layer

plugins {
    id("gst.library")
    id("java-library") // `api(...)`: tenancy-kernel, audit-log and outbox-and-queue types are in public signatures
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
evaluationDependsOn(":audit-log")
evaluationDependsOn(":outbox-and-queue")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")
val auditLogImplJar = project(":audit-log").tasks.named("implJar")
val outboxImplJar = project(":outbox-and-queue").tasks.named("implJar")

dependencies {
    api(project(":tenancy-kernel"))
    // INV-T-2870 / T26: the write path writes its audit entry through audit-log's AuditWriter and
    // PolicyChanged through outbox-and-queue's OutboxWriter, where the service has one (INV-MOD-2402,
    // INV-Q-3142). Only their `-api` halves: the service binds the implementations.
    api(project(":audit-log"))
    api(project(":outbox-and-queue"))
    add("implImplementation", project(":tenancy-kernel"))
    add("implImplementation", project(":audit-log"))
    add("implImplementation", project(":outbox-and-queue"))
    add("implImplementation", files(tenancyKernelImplJar))

    // STK-08: Jackson 3 (`tools.jackson`) — the typed option values (jsonb).
    add("implImplementation", libs.jackson.databind)

    // Tests run against real PostgreSQL 16, 17 and 18 (STK-14, STK-15, INV-Q-3965; H2 is banned).
    testImplementation(sourceSets["impl"].output)
    testImplementation(files(tenancyKernelImplJar))
    // INV-T-3983: the installation write's staged rows come from the real stage calls (AuditStager over the outbox,
    // or the local audit_entry; OutboxWriter.stage), into the real outbox and audit_entry tables of the test schema.
    testImplementation(files(auditLogImplJar))
    testImplementation(files(outboxImplJar))
    testImplementation(libs.jackson.databind)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
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
