// settings.gradle.kts — svc-access (TPL-01, STK-04, STK-72; INV-T-4934).
//
// The gst.* convention plugins and platform-build's Konsist rule set are consumed as PUBLISHED artefacts of
// platform-build's package registry (project 225) at the catalogue's `platformBuild` version, so a build never floats
// to an unreleased platform-build (P11). In CI the job token reads the registry; locally export GST_REGISTRY_TOKEN.
// The LAN registry has no TLS (a Wave 5 item), so http is opted into for that one host only.
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
        id("gst.contract-server") version platformBuildVersion
        id("gst.domain") version platformBuildVersion
        id("gst.application") version platformBuildVersion
        id("gst.adapters") version platformBuildVersion
        id("gst.bootstrap") version platformBuildVersion
        id("gst.jooq-codegen") version platformBuildVersion
    }
}

// STK-72: the settings classpath is locked in strict mode; a deleted lock file or a missing entry fails the build.
// Gradle writes the version catalogue's own lock state to the default settings-gradle.lockfile, so the settings
// classpath lock lives in gradle/settings-gradle.lockfile (the same split as platform-libs).
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
        // platform-libs (project 227): the platform modules and their BOM (STK-60).
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
        // platform-contracts' registry (project 226): gst-catalogue and the published contracts bundle (STK-61, STK-46).
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
        // The build-local Maven layout access-contract stages from that bundle (`stageContracts`), which
        // gst.contract-server resolves `platform-contracts:access:+` from. The group resolves from this staging
        // repository only (exclusiveContent) and is trusted in the verification metadata for that group only.
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedPlatformContracts"
                    url = uri(settingsDir.resolve("access-contract/build/contracts-repo"))
                }
            }
            filter { includeGroup("platform-contracts") }
        }
        mavenCentral()
    }
}

rootProject.name = "svc-access"

// The five architectural modules of TPL-01 / ARC-10; their tests, including the acceptance-criterion tests, live in them.
include(
    "access-contract",
    "access-domain",
    "access-application",
    "access-adapters",
    "access-bootstrap",
)
