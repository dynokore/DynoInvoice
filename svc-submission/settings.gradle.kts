// settings.gradle.kts — svc-submission (TPL-01; INV-T-5964).
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
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
        // gst.contract-server resolves `platform-contracts:submission:+` from (ContractSource.kt).
        exclusiveContent {
            forRepository {
                maven {
                    name = "stagedPlatformContracts"
                    url = uri(settingsDir.resolve("submission-contract/build/contracts-repo"))
                }
            }
            filter { includeGroup("platform-contracts") }
        }
        mavenCentral()
    }
}

rootProject.name = "svc-submission"

include(
    "submission-contract",
    "submission-domain",
    "submission-application",
    "submission-adapters",
    "submission-bootstrap",
    "acceptance", // test-only (TPL-01): not one of the five architectural modules ARC-10 counts
)
