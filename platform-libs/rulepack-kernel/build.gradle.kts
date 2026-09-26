// rulepack-kernel/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6;
// INV-T-2860 / T16 scaffold, INV-T-2866 / T22: signed artefact, verification and
// effective-dated resolution).
//
// One Gradle project, one version, one changelog for both halves of its
// api/impl split:
//   - `src/main` (published as `rulepack-kernel-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on.
//   - `src/impl` (published as `rulepack-kernel`) — the implementation, built on top
//     of `src/main`'s output; depended on only through `service-runtime`'s
//     composition root or `product-runtime` (TPL-01, TPL-18), never by another
//     L1/L2 module.
//
// Order within L1 (INV-MOD-2373, INV-ADR-2774): uses `tenancy-kernel` only, for
// its `TenantId` value type ([PackVersion]/[PackRegistry]'s tenant-precedence
// resolution, INV-REQ-770) — no other L1 module. Anything else this module
// might need (emitting a platform event, for a later task's fence participant)
// goes through a port it declares itself, never a further `project(":...")`
// dependency. `implementation`, not `api`: no `java-library` plugin is applied
// in this repository yet (no other module has needed one), so a consumer of
// the published `rulepack-kernel-api` artefact that needs `TenantId` directly
// declares `tenancy-kernel-api` itself — every real consumer already does, for
// its own tenant-context needs (INV-MOD-2373 "Embedded by").

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
    implementation(project(":tenancy-kernel"))

    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`, e.g. `testImplementation(sourceSets.main.get().output)`).
    add("implImplementation", sourceSets["main"].output)
    // `main`'s own `implementation(project(":tenancy-kernel"))` is not visible to a
    // sibling custom source set (Gradle wires each source set's own configurations
    // independently) — `InMemoryPackRegistry` (impl) needs `TenantId` directly too.
    add("implImplementation", project(":tenancy-kernel"))

    // STK-13: detached CMS signature verification, ECDSA P-256 with SHA-256.
    add("implImplementation", libs.bouncycastle.bcprov)
    add("implImplementation", libs.bouncycastle.bcpkix)
    // STK-08: Jackson 3 (`tools.jackson`) — manifest, checksums and pack-content parsing.
    add("implImplementation", libs.jackson.databind)

    testImplementation(sourceSets["impl"].output)
    testImplementation(project(":tenancy-kernel"))
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutinesTest)
    testRuntimeOnly(libs.junit.platformLauncher)

    // Real PostgreSQL only, in every environment including tests (STK-14, STK-15;
    // H2 is banned outright, banned-dependencies.yaml) — proves PackRegistryMigration's
    // RLS policy against the actual database CS-19 targets, not string matching.
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.slf4j.simple) // Testcontainers' own container/strategy logs are otherwise silently dropped (NOP logger)
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
