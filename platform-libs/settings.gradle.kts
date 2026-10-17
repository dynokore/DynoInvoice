// settings.gradle.kts — STK-04, STK-60 (INV-T-2860 / T16).
//
// The `gst.*` convention plugins (`gst.library`, STK-59) and platform-build's shared Konsist rule
// set (`konsist-rules`) are consumed as PUBLISHED artefacts of platform-build, from its package
// registry (project 225), at the catalogue's `platformBuild` version: a build never floats to an
// unreleased platform-build (P11). In CI the job token reads the registry (platform-build's
// inbound token allowlist includes the `invoice` group); locally export GST_REGISTRY_TOKEN, a
// personal access token with read_api. The LAN registry has no TLS (a Wave 5 item), so http is
// opted into for that one host only. The two blocks below repeat the repository because
// `pluginManagement` is evaluated before any script-level declaration.
pluginManagement {
    val platformBuildVersion =
        Regex("""(?m)^platformBuild\s*=\s*"([^"]+)"""")
            .find(settings.settingsDir.resolve("gradle/libs.versions.toml").readText())
            ?.groupValues
            ?.get(1)
            ?: error("gradle/libs.versions.toml has no platformBuild version")
    repositories {
        maven {
            name = "platformBuildRegistry"
            url = uri("http://registry.example.internal/api/v4/projects/225/packages/maven")
            isAllowInsecureProtocol = url.scheme == "http" && url.host == "registry.example.internal"
            val jobToken = System.getenv("CI_JOB_TOKEN")
            credentials(HttpHeaderCredentials::class) {
                name = if (jobToken != null) "Job-Token" else "Private-Token"
                value = jobToken ?: System.getenv("GST_REGISTRY_TOKEN") ?: ""
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("gst.library") version platformBuildVersion
        // product-runtime's generated contract types and fallback clients (INV-T-2876, INV-Q-3852; STK-23, STK-74).
        id("gst.event-types") version platformBuildVersion
        id("gst.contract-client") version platformBuildVersion
        // contract-tests (INV-T-2877, INV-Q-3925): a test-only Kotlin module (gst.kotlin-base) that publishes its
        // pacts to the Pact Broker (gst.pact, STK-40; INV-Q-3305, INV-T-3373).
        id("gst.kotlin-base") version platformBuildVersion
        id("gst.pact") version platformBuildVersion
    }
}

// STK-72 (INV-T-4297, coordinator ruling INV-Q-4367): the settings classpath (the plugin below) is
// locked in strict mode; a deleted lock file or a missing entry fails the build. Its lock state lives in
// gradle/settings-gradle.lockfile, because Gradle writes the version catalogue's own settings lock state
// (incomingCatalogForLibs0) to the default settings-gradle.lockfile and the last writer would drop the
// classpath entries (the same split as platform-contracts' codegen-self-test/gradle/settings-gradle.lockfile).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
        lockFile.set(file("gradle/settings-gradle.lockfile"))
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// STK-72 (INV-T-4297, coordinator ruling INV-Q-4375): the root settings-gradle.lockfile holds only the version
// catalogue's lock state, which Gradle does not check strictly; this makes a deleted or changed file fail the
// build. Skipped under --write-locks, which rewrites the file.
if (!gradle.startParameter.isWriteDependencyLocks) {
    val catalogueLock = settingsDir.resolve("settings-gradle.lockfile")
    val lockLines =
        catalogueLock.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter {
            it.isNotEmpty() &&
                !it.startsWith("#")
        }
    check(lockLines == listOf("empty=incomingCatalogForLibs0")) {
        "STK-72: settings-gradle.lockfile must exist and hold exactly 'empty=incomingCatalogForLibs0' " +
            "(found ${lockLines ?: "no file"}); regenerate it as README.md, \"Dependency locking and verification\" says"
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            name = "platformBuildRegistry"
            url = uri("http://registry.example.internal/api/v4/projects/225/packages/maven")
            isAllowInsecureProtocol = url.scheme == "http" && url.host == "registry.example.internal"
            val jobToken = System.getenv("CI_JOB_TOKEN")
            credentials(HttpHeaderCredentials::class) {
                name = if (jobToken != null) "Job-Token" else "Private-Token"
                value = jobToken ?: System.getenv("GST_REGISTRY_TOKEN") ?: ""
            }
            authentication { create<HttpHeaderAuthentication>("header") }
            content { includeGroup("com.dynokore.gst.build") } // konsist-rules only
        }
        // platform-contracts' registry (project 226; INV-T-2876, coordinator ruling G3): the gst-catalogue artefact
        // (STK-61) and the published platform-contracts bundle product-runtime generates its contract types from
        // (INV-Q-3852). Its inbound job-token allowlist includes the `invoice` group, as platform-build's does.
        maven {
            name = "platformContractsRegistry"
            url = uri("http://registry.example.internal/api/v4/projects/226/packages/maven")
            isAllowInsecureProtocol = url.scheme == "http" && url.host == "registry.example.internal"
            val jobToken = System.getenv("CI_JOB_TOKEN")
            credentials(HttpHeaderCredentials::class) {
                name = if (jobToken != null) "Job-Token" else "Private-Token"
                value = jobToken ?: System.getenv("GST_REGISTRY_TOKEN") ?: ""
            }
            authentication { create<HttpHeaderAuthentication>("header") }
            content { includeModule("com.dynokore.gst", "gst-catalogue") }
        }
        ivy {
            name = "platformContractsBundle"
            url = uri("http://registry.example.internal/api/v4/projects/226/packages/generic")
            isAllowInsecureProtocol = url.scheme == "http" && url.host == "registry.example.internal"
            patternLayout { artifact("[module]/[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            val jobToken = System.getenv("CI_JOB_TOKEN")
            credentials(HttpHeaderCredentials::class) {
                name = if (jobToken != null) "Job-Token" else "Private-Token"
                value = jobToken ?: System.getenv("GST_REGISTRY_TOKEN") ?: ""
            }
            authentication { create<HttpHeaderAuthentication>("header") }
            content { includeGroup("platform-contracts-bundle") }
        }
        // The build-local Maven layout product-runtime stages from that bundle (`stageContracts`), which
        // gst.event-types and gst.contract-client resolve `platform-contracts:<service>:+` from (ContractSource.kt).
        // STK-72 (INV-T-4297): the group resolves from this staging repository only (exclusiveContent), so no
        // remote artefact of it can ever resolve. Its files are unpacked from the bundle above, which is
        // checksum-verified, so gradle/verification-metadata.xml trusts this one group (trusted-artifacts) rather
        // than pinning per-version copies that change with every bundle.
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedPlatformContracts"
                    url = uri(settingsDir.resolve("product-runtime/build/contracts-repo"))
                }
            }
            filter { includeGroup("platform-contracts") }
        }
        mavenCentral()
    }
}

rootProject.name = "platform-libs"

// Wave 1 modules only (repository README.md's wave table; AGT-11: start a task
// only when its dependencies are merged). Wave 2-4 modules (party-registry,
// calendar-and-periods, document-lifecycle, integration-canon,
// reconciliation-engine) stay README-only directories until their own Keel task
// scaffolds them.
include(
    "platform-libs-bom",
    "service-runtime",
    "outbox-and-queue",
    "rulepack-kernel",
    "tenancy-kernel",
    "evidence-store",
    "adapter-runtime",
    "config-resolver",
    "audit-log",
    "crypto", // CS-53, INV-Q-3356 M-A (INV-T-3358): the lowest L1 module
    "tenancy-jooq", // INV-T-4133: the framework tenant predicate on jOOQ (INV-ADR-3968 item 4a)
    "product-runtime",
    "calendar-and-periods", // INV-T-4963: the L2 period and deadline library (INV-MOD-2742)
    "party-registry", // INV-T-4964: the L2 party model library (INV-MOD-2740)
    "document-lifecycle", // INV-T-5938: the L2 document lifecycle library (INV-MOD-2741)
    // The consumer-driven contract suite of every Wave 1 module (INV-T-2877, coordinator ruling INV-Q-3925): Pact for
    // the wire edges whose consumer code is here, module-API consumer contract tests for every other boundary. Test
    // only: it publishes no artefact and is not in platform-libs-bom.
    "contract-tests",
    // INV-T-5604: the digest pin table of the Testcontainers images (postgres:16/17/18, Ryuk), on every module's Test
    // runtime classpath (build.gradle.kts). Test only: it publishes no artefact and is not in platform-libs-bom.
    "testcontainers-pins",
)

// product-runtime's generated contract code (INV-T-2876, INV-Q-3852): one project per generator source, as
// gst.event-types and gst.contract-client take exactly one; each project's name becomes the package segment of its
// event, api and model types (com.dynokore.gst.productRuntime...), so those never clash with a service's own generated
// tenancy or access types. The openapi-generator infrastructure package (org.openapitools.client.infrastructure) is
// not segmented by platform-build 0.6.1: every gst.contract-client project, product-runtime's and a service's own,
// generates it under the same names, so a service that embeds product-runtime and generates a client of its own has
// two copies on its classpath (platform-build follow-up: a per-project infrastructure package). Their classes ship
// inside product-runtime's impl artefact, with one copy of that package (product-runtime/build.gradle.kts).
listOf(
    "product-runtime-tenancy-client" to "tenancy-client",
    "product-runtime-access-client" to "access-client",
    "product-runtime-structure-events" to "structure-events",
    "product-runtime-profile-events" to "profile-events",
    "product-runtime-authority-events" to "authority-events",
    "product-runtime-policy-events" to "policy-events",
).forEach { (name, dir) ->
    include(name)
    project(":$name").projectDir = settingsDir.resolve("product-runtime/contracts/$dir")
}
