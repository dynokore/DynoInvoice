// service-runtime/build.gradle.kts — STK-60 L1 module (ARC-16, FR-L.6; INV-T-2860 /
// T16 scaffold; INV-T-2871 / T27 gives it its real interfaces).
//
// service-runtime is the L1 composition root (INV-MOD-2371) every service's bootstrap
// imports (TPL-18): it may use the other L1 modules its service embeds, and no other L1
// module depends on it (`CompositionRootDirectionTest`). INV-T-2875 wires each embedded
// module's ports (`composition/`); the modules it does not otherwise use are compileOnly
// here, so a service gets only the modules it puts on its own classpath. This train adds: Spring Boot 4.1
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

// Project evaluation order is alphabetical, so these modules' `implJar` tasks would not exist yet (as audit-log).
evaluationDependsOn(":tenancy-kernel")
evaluationDependsOn(":outbox-and-queue")
evaluationDependsOn(":audit-log")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")
val outboxImplJar = project(":outbox-and-queue").tasks.named("implJar")
val auditLogImplJar = project(":audit-log").tasks.named("implJar")

// INV-T-2875 (INV-Q-3710): the composition root wires every L1 module the service embeds. The modules (and impl
// halves) service-runtime does not already depend on are compileOnly in the impl half, gated by
// @ConditionalOnClass; implTest puts every one on the classpath ("every L1 module present").
evaluationDependsOn(":config-resolver")
evaluationDependsOn(":crypto")
evaluationDependsOn(":rulepack-kernel")
evaluationDependsOn(":evidence-store")
evaluationDependsOn(":adapter-runtime")
val optionalL1Modules = listOf(":crypto", ":rulepack-kernel", ":evidence-store", ":adapter-runtime")
// The impl halves' class output, not their `implJar`: rulepack-kernel's and adapter-runtime's `implJar` write to the
// same build/libs file as their api `jar`, so whichever runs last wins.
val optionalImplOutputs =
    (optionalL1Modules + listOf(":config-resolver", ":audit-log")).map {
        project(it).the<SourceSetContainer>()["impl"].output
    }

dependencies {
    // INV-T-2873 / T29: the replicas are keyed by tenancy-kernel's `TenantId`. service-runtime
    // is the L1 composition root (INV-MOD-2371): it may use the other L1 modules, none may use it.
    api(project(":tenancy-kernel"))
    add("implImplementation", project(":tenancy-kernel"))
    add("implTestImplementation", project(":tenancy-kernel"))

    // INV-T-3002: PolicyReplica resolves over config-resolver's scope chain (INV-REQ-1242), api half only.
    api(project(":config-resolver"))
    // INV-T-2875: the tolerant PolicyChanged reader builds the replica's PolicyScope in the impl half.
    add("implImplementation", project(":config-resolver"))
    add("implTestImplementation", project(":config-resolver"))

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
        // INV-T-2872 / T28: ARC-08's generated client (`spring-web`'s `RestClient`),
        // API-29/STK-26's server-side filters (the servlet API only, no
        // container), and STK-26's workload-token verification.
        add(config, libs.springWeb)
        add(config, libs.jakarta.servletApi)
        add(config, libs.springSecurity.oauth2Jose)
    }
    add("implTestImplementation", libs.springBoot.test)
    // INV-T-3881 (INV-Q-3889): a TPL-16 service's application DataSource comes from `spring.datasource.*` through
    // Spring Boot's DataSourceAutoConfiguration (HikariCP, STK-17); the composition tests build it that way.
    add("implTestImplementation", libs.springBoot.starterJdbc)

    // INV-T-2874 / T30: the job and connection contracts. The api half names outbox-and-queue's `EntityRef`
    // (the entity of a job target); the impl half writes JobRegistered, JobRunRecorded and
    // ConnectionStatusChanged through outbox-and-queue's `OutboxWriter` and the command audit entries
    // through audit-log's `AuditWriter`, in transactions of tenancy-kernel's `TransactionRunner`
    // (`CurrentConnection`, `TenantRlsMigration` and `TenantScope` live in those modules' impl halves,
    // wired as audit-log wires them). ShedLock 6 on PostgreSQL is the leader election (STK-25,
    // INV-REQ-2405); Jackson 3 writes the payloads (STK-08).
    api(project(":outbox-and-queue"))
    add("implImplementation", project(":outbox-and-queue"))
    add("implImplementation", project(":audit-log"))
    add("implImplementation", files(tenancyKernelImplJar))
    add("implImplementation", files(outboxImplJar))
    add("implTestImplementation", project(":outbox-and-queue"))
    add("implTestImplementation", project(":audit-log"))
    add("implTestImplementation", files(tenancyKernelImplJar))
    add("implTestImplementation", files(outboxImplJar))
    add("implTestImplementation", testFixtures(project(":outbox-and-queue")))
    listOf("implImplementation", "implTestImplementation").forEach { config ->
        add(config, libs.shedlock.core)
        add(config, libs.shedlock.providerJdbcTemplate)
        add(config, libs.spring.jdbc)
        add(config, libs.jackson.databind)
    }
    add("implTestImplementation", libs.junit.params)
    add("implTestImplementation", libs.postgresql)
    add("implTestImplementation", libs.testcontainers.postgresql)
    // Spring Boot's BOM manages Testcontainers 2.x; outbox-and-queue's fixture is built on the repository's
    // 1.21 line (libs.versions.toml), so the core artifact is held to it here.
    constraints {
        add("implTestImplementation", "org.testcontainers:testcontainers") {
            version { strictly(libs.versions.testcontainers.get()) }
        }
    }
    add("implTestImplementation", libs.assertj.core)

    // INV-T-2875: the composition root's optional L1 modules (see `optionalL1Modules` above).
    optionalL1Modules.forEach {
        add("implCompileOnly", project(it))
        add("implTestImplementation", project(it))
    }
    optionalImplOutputs.forEach {
        add("implCompileOnly", it)
        add("implTestImplementation", it)
    }
    add("implCompileOnly", libs.awssdk.s3)
    add("implTestImplementation", libs.awssdk.s3)
    add("implTestImplementation", libs.bouncycastle.bcprov)
    add("implTestImplementation", libs.bouncycastle.bcpkix)

    // STK-24 (INV-T-2872 / T28): Resilience4j core for the internal-edge
    // `ResilienceRegistry` — the same modules `adapter-runtime` (T18) already
    // uses for its own, external-edge executor.
    listOf("implImplementation", "implTestImplementation").forEach { config ->
        add(config, platform(libs.resilience4j.bom))
        add(config, libs.resilience4j.circuitbreaker)
        add(config, libs.resilience4j.retry)
        add(config, libs.resilience4j.bulkhead)
        add(config, libs.resilience4j.timelimiter)
    }

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
