// ci/consumer-check — INV-T-4356: an outside consumer of platform-libs (STK-60, STK-01 rule (a), TPL-01).
//
// A Gradle build of its own, outside the platform-libs build: it declares only `platform(platform-libs-bom)` at the
// version the main pipeline just published and the impl artefacts of five L1 modules (build.gradle.kts), and must
// resolve the rest of the graph transitively from the published POMs. It runs in the main pipeline's
// `consumer-check` job after maven-publish (.gitlab-ci.yml), which also writes this build's
// gradle/verification-metadata.xml (strict) from the repository's own before it runs.
rootProject.name = "platform-libs-consumer-check"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // platform-libs' own package registry (project 227) is the only source of the com.dynokore.platform group.
        exclusiveContent {
            forRepository {
                maven {
                    name = "platformLibsRegistry"
                    url =
                        uri(
                            providers
                                .gradleProperty("platformLibsRegistry")
                                .getOrElse("http://registry.example.internal/api/v4/projects/227/packages/maven"),
                        )
                    // The LAN registry has no TLS (a Wave 5 item): http is opted into for that one host only.
                    isAllowInsecureProtocol = url.scheme == "http" && url.host == "registry.example.internal"
                    if (url.scheme == "http") {
                        val jobToken = System.getenv("CI_JOB_TOKEN")
                        credentials(HttpHeaderCredentials::class) {
                            name = if (jobToken != null) "Job-Token" else "Private-Token"
                            value = jobToken ?: System.getenv("GST_REGISTRY_TOKEN") ?: ""
                        }
                        authentication { create<HttpHeaderAuthentication>("header") }
                    }
                }
            }
            filter { includeGroup("com.dynokore.platform") }
        }
        mavenCentral()
    }
}
