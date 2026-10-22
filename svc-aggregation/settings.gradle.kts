// settings.gradle.kts — svc-aggregation (TPL-01; INV-T-8451).
//
// The gst.* convention plugins come from platform-build's package registry (project 225) at the catalogue's
// `platformBuild` version, never a sibling checkout. In CI the job token reads the registry; locally export
// GST_REGISTRY_TOKEN. The LAN registry has no TLS (a Wave 5 item), so http is opted into for that one host only.
pluginManagement {
    val platformBuildVersion =
        Regex("""(?m)^platformBuild\s*=\s*"([^"]+)"""")
            .find(settings.settingsDir.resolve("gradle/libs.versions.toml").readText())
            ?.groupValues
            ?.get(1)
            ?: error("gradle/libs.versions.toml has no platformBuild version")
    val springBootVersion =
        Regex("""(?m)^springBoot\s*=\s*"([^"]+)"""")
            .find(settings.settingsDir.resolve("gradle/libs.versions.toml").readText())
            ?.groupValues
            ?.get(1)
            ?: error("gradle/libs.versions.toml has no springBoot version")
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
        id("gst.kotlin-base") version platformBuildVersion
        id("gst.domain") version platformBuildVersion
        id("gst.application") version platformBuildVersion
        id("gst.adapters") version platformBuildVersion
        id("gst.bootstrap") version platformBuildVersion
        id("gst.contract-server") version platformBuildVersion
        id("gst.jooq-codegen") version platformBuildVersion
        // the Spring Boot Gradle plugin gst.bootstrap hooks bootBuildImage onto (STK-53); the Boot version of the catalogue.
        id("org.springframework.boot") version springBootVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// STK-72: the settings classpath (the plugin above) is locked in strict mode; a deleted lock file or a missing entry
// fails the build. Its lock state lives in gradle/settings-gradle.lockfile, because Gradle writes the version
// catalogue's own settings lock state (incomingCatalogForLibs0) to the default settings-gradle.lockfile and the last
// writer would drop the classpath entries (the same split as platform-libs).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
        lockFile.set(file("gradle/settings-gradle.lockfile"))
    }
}

// STK-72: the root settings-gradle.lockfile holds only the version catalogue's lock state, which Gradle does not check
// strictly; this makes a deleted or changed file fail the build. Skipped under --write-locks, which rewrites the file.
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
        // platform-build's konsist-rules (group com.dynokore.gst.build).
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
            content { includeGroup("com.dynokore.gst.build") }
        }
        // platform-libs (project 227): the L1 modules and their BOM.
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
        // platform-contracts (project 226): the gst-catalogue artefact and the contracts bundle.
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
        // The build-local Maven layout the root build stages from that bundle (`stageContracts`), which
        // gst.contract-server resolves `platform-contracts:aggregation:+` from (ContractSource.kt).
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedPlatformContracts"
                    url = uri(settingsDir.resolve("aggregation-contract/build/contracts-repo"))
                }
            }
            filter { includeGroup("platform-contracts") }
        }
        mavenCentral()
    }
}

rootProject.name = "svc-aggregation"

include(
    "aggregation-contract",
    "aggregation-domain",
    "aggregation-application",
    "aggregation-adapters",
    "aggregation-bootstrap",
    "acceptance", // test-only (TPL-01): not one of the five architectural modules ARC-10 counts
)
