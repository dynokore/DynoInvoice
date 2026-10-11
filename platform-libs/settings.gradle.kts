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
            content { includeGroup("com.dynokore.gst.build") } // konsist-rules only
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
    "product-runtime",
)
