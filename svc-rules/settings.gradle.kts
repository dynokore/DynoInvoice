// settings.gradle.kts — TPL-01, STK-04, STK-72 (INV-T-4965).
//
// The `gst.*` convention plugins (STK-59) and platform-build's shared Konsist rule
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
        // TPL-01: the five architectural modules' convention plugins, at the catalogue's platformBuild version.
        id("gst.kotlin-base") version platformBuildVersion
        id("gst.contract-server") version platformBuildVersion
        id("gst.domain") version platformBuildVersion
        id("gst.application") version platformBuildVersion
        id("gst.adapters") version platformBuildVersion
        id("gst.bootstrap") version platformBuildVersion
        id("gst.jooq-codegen") version platformBuildVersion
        // The Spring Boot plugin (bootJar, bootBuildImage) of rules-bootstrap, at the catalogue's springBoot version.
        id("org.springframework.boot") version
            Regex("""(?m)^springBoot\s*=\s*"([^"]+)"""")
                .find(settings.settingsDir.resolve("gradle/libs.versions.toml").readText())!!
                .groupValues[1]
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
            "(found ${lockLines ?: "no file"}); regenerate it as README.md says"
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
        // platform-libs' registry (project 227): the published L1 modules and the platform-libs-bom (TPL-01).
        maven {
            name = "platformLibsRegistry"
            url = uri("http://registry.example.internal/api/v4/projects/227/packages/maven")
            isAllowInsecureProtocol = url.scheme == "http" && url.host == "registry.example.internal"
            val jobToken = System.getenv("CI_JOB_TOKEN")
            credentials(HttpHeaderCredentials::class) {
                name = if (jobToken != null) "Job-Token" else "Private-Token"
                value = jobToken ?: System.getenv("GST_REGISTRY_TOKEN") ?: ""
            }
            authentication { create<HttpHeaderAuthentication>("header") }
            content { includeGroup("com.dynokore.platform") }
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
        // The build-local Maven layout rules-contract stages from that bundle (`stageContracts`), which
        // gst.contract-server resolves `platform-contracts:<service>:+` from (ContractSource.kt).
        // STK-72 (INV-T-4297): the group resolves from this staging repository only (exclusiveContent), so no
        // remote artefact of it can ever resolve. Its files are unpacked from the bundle above, which is
        // checksum-verified, so gradle/verification-metadata.xml trusts this one group (trusted-artifacts) rather
        // than pinning per-version copies that change with every bundle.
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedPlatformContracts"
                    url = uri(settingsDir.resolve("rules-contract/build/contracts-repo"))
                }
            }
            filter { includeGroup("platform-contracts") }
        }
        mavenCentral()
    }
}

rootProject.name = "svc-rules"

include("rules-contract", "rules-domain", "rules-application", "rules-adapters", "rules-bootstrap")
