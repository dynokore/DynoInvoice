// evidence-store/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6;
// INV-T-2860 / T16 scaffold, INV-T-2863 / T19 real interface).
//
// Append-only, hash-chained evidence ledger, indexed retrieval and bulk export
// scoped to entity and period, retention with legal hold, and the ObjectStore
// port over object-lock storage (INV-REQ-2570, FR-9.15, FR-D.1-D.4). Uses no
// other L1 module but tenancy-kernel (Keel INV-ADR-2774) — its
// TransactionRunner/TenantContext ports to run inside the caller's tenant
// transaction, and its TenantRlsMigration reused directly to generate this
// module's own RLS trailer, rather than a second copy of that helper.
//
// One Gradle project, one version, one changelog for both halves of its
// api/impl split:
//   - `src/main` (published as `evidence-store-api`) — ports and value types only,
//     framework-free (ARC-16); the only half another module or a service may
//     depend on. Depends on tenancy-kernel's own `-api` half (its default
//     project output) for `TenantId`/`TransactionRunner`/`TenantContext`.
//   - `src/impl` (published as `evidence-store`) — the JDBC ledger and legal-hold
//     repositories, the migration-trailer generator, and the AWS SDK v2
//     ObjectStore adapter; depended on only through `service-runtime`'s
//     composition root or `product-runtime` (TPL-01, TPL-18), never by another
//     L1/L2 module.
//   - `src/testFixtures` (`java-test-fixtures`) — this module's own isolation-test
//     fixture (a fresh Testcontainers PostgreSQL instance and schema), reusing
//     tenancy-kernel's `TenantAwareDataSource` and `TenantRlsMigration` directly
//     rather than reimplementing them (CODER-BRIEF), plus its own
//     `testFixtures(project(":tenancy-kernel"))` dependency for the same reason.
//
// tenancy-kernel's own `-impl` half (`TenantRlsMigration`, `CurrentConnection`)
// is not published to a registry yet (settings.gradle.kts) and is not exposed
// as a separate consumable Gradle configuration by tenancy-kernel's own build
// script — only its Maven publications and its `implJar` task. So this
// module-to-module dependency, "at the same commit" (repository README.md),
// is that compiled jar's task output directly: the same sibling-project
// technique `platform-libs-bom`'s own `project(":tenancy-kernel").version`
// already uses, just for compiled classes instead of a version string.

import gst.build.Layer

plugins {
    id("gst.library")
    id("java-library") // for the `api(...)` configuration below: evidence-store-api's own public
    // types (EvidenceRecord, the ports) carry tenancy-kernel's TenantId in their
    // signatures, so that dependency must be compile-visible to a downstream
    // consumer too, not just runtime-visible (`implementation` alone would not
    // propagate it into this module's own `apiElements` variant).
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

// Gradle's default project-evaluation order is alphabetical by directory, not
// settings.gradle.kts's `include(...)` order (STK-60's L1 order) — "evidence-store"
// otherwise evaluates before "tenancy-kernel" ('e' < 't') and its `implJar` task
// would not exist yet. Forced explicitly, the same as any other same-commit,
// same-repository project dependency on a task (not just a lazy property) needs.
evaluationDependsOn(":tenancy-kernel")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")
evaluationDependsOn(":tenancy-jooq")
val tenancyJooqImplJar = project(":tenancy-jooq").tasks.named("implJar")

dependencies {
    // The impl half compiles and runs against the api half's output, in this
    // same project (Gradle's own idiom for a second source set that extends
    // `main`).
    add("implImplementation", sourceSets["main"].output)
    add("testFixturesImplementation", sourceSets["impl"].output)

    // -api half: tenancy-kernel's default project output is its own `-api` half
    // (main sourceSet) — TenantId, TenantContext, TransactionRunner (ARC-16,
    // both framework-free).
    api(project(":tenancy-kernel"))
    // ContentHash hashes through the platform crypto module (CS-53; INV-T-3358): its pure-JDK
    // `-api` half only, which this module's own public types never expose.
    implementation(project(":crypto"))

    // -impl half: `sourceSets["main"].output` above is only this project's own
    // compiled classes, not `main`'s own dependencies (Gradle does not follow
    // that transitively) — tenancy-kernel's `-api` half is declared again here,
    // explicitly, the same as TPL-01 declares `tenancyKernel.api` separately
    // for each of a service's own modules that need it. Plus the JDBC ledger's
    // own need for CurrentConnection (tenancy-kernel impl), and the
    // migration-trailer generator's reuse of TenantRlsMigration (impl) directly
    // (CODER-BRIEF) rather than duplicating its RLS DDL. `-api` on testFixtures
    // so the `test` source set (auto-wired to testFixtures within this same
    // project, the way tenancy-kernel's own `test` already relies on for its
    // `testFixturesApi(libs.postgresql)`) sees it too, without a second
    // declaration.
    add("implImplementation", project(":tenancy-kernel"))
    add("implImplementation", project(":crypto"))
    add("implImplementation", files(tenancyKernelImplJar))
    testFixturesApi(files(tenancyKernelImplJar))
    // INV-T-4516 (INV-ADR-3968 item 4a, owner ruling INV-Q-4196): every statement is jOOQ on tenancy-jooq's
    // DSLContext; tenancy-jooq-api brings jOOQ.
    add("implImplementation", project(":tenancy-jooq"))
    add("implImplementation", files(tenancyJooqImplJar))
    testImplementation(project(":tenancy-jooq"))
    testImplementation(files(tenancyJooqImplJar))
    testImplementation(testFixtures(project(":tenancy-jooq")))
    testImplementation(sourceSets["impl"].output)

    // Isolation-test fixtures: a real PostgreSQL Testcontainers instance
    // (STK-14, STK-15; H2 is banned outright) plus tenancy-kernel's own
    // `TenantAwareDataSource`, reused rather than reimplemented.
    testFixturesApi(libs.postgresql)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(testFixtures(project(":tenancy-kernel")))

    // ObjectStore: AWS SDK v2 against the S3 API (STK-20) — never io.minio
    // (banned-dependencies.yaml); MinIO in Testcontainers stands in for the
    // object-lock simulator until the certification-kit image exists
    // (done-when).
    add("implImplementation", libs.awssdk.s3)
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.testcontainers.minio)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testImplementation(libs.platform.konsistRules) // the architecture test (TPL-14) only
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutinesCore)
    testRuntimeOnly(libs.junit.platformLauncher)
    // Testcontainers' own container/strategy logs are otherwise silently dropped (NOP logger)
    testRuntimeOnly(libs.slf4j.simple)
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
        // A separate local output directory (not a classifier — see tenancy-kernel's
        // own `implJar`, same fix, for why): the default `jar` task's output would
        // otherwise collide with this one on disk under `build/libs/` (same base
        // name, same version), which is exactly the bug this module's own
        // cross-module dependency on tenancy-kernel's `implJar` surfaced there.
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
