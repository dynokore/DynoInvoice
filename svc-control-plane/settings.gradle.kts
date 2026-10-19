// settings.gradle.kts: TPL-01. The gst.* convention plugins and platform-build's konsist-rules are consumed as PUBLISHED
// artefacts of platform-build's package registry (project 225) at the catalogue's `platformBuild` version, so a build never
// floats to an unreleased platform-build. In CI the job token reads the registry; locally export GST_REGISTRY_TOKEN
// (a personal access token with read_api). The LAN registry has no TLS, so http is opted into for that one host only.
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
        id("gst.domain") version platformBuildVersion
        id("gst.application") version platformBuildVersion
        id("gst.adapters") version platformBuildVersion
        id("gst.bootstrap") version platformBuildVersion
        id("gst.contract-server") version platformBuildVersion
        id("gst.jooq-codegen") version platformBuildVersion
        id("gst.kotlin-base") version platformBuildVersion
        id("org.springframework.boot") version "4.1.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
            content { includeGroup("com.dynokore.gst.build") }
        }
        // platform-libs' registry (project 227): the BOM and the L1/L3 modules this service composes (STK-60).
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
        // platform-contracts' registry (project 226): the gst-catalogue artefact and the contracts bundle.
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
        // The build-local Maven layout control-plane-contract stages from that bundle: gst.contract-server resolves
        // `platform-contracts:control-plane:+` from it. The group resolves from this staging repository only, and
        // gradle/verification-metadata.xml trusts that one group (its files come from the checksum-verified bundle).
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedPlatformContracts"
                    url = uri(settingsDir.resolve("control-plane-contract/build/contracts-repo"))
                }
            }
            filter { includeGroup("platform-contracts") }
        }
        mavenCentral()
    }
}

rootProject.name = "svc-control-plane"

// TPL-01: the five architectural modules (ARC-10) and the test-only `acceptance` project, which also holds the two
// repository gates (operations.tsv, ac-keys.tsv).
include(
    "control-plane-contract",
    "control-plane-domain",
    "control-plane-application",
    "control-plane-adapters",
    "control-plane-bootstrap",
    "acceptance",
)
