// settings.gradle.kts — INV-T-2850 follow-up ("contracts-codegen-compile", T6 follow-up).
//
// Proves, for every service directory in platform-contracts, that platform-build's
// code-generation plugins (gst.contract-server, gst.contract-client, gst.event-types;
// INV-T-2847) actually generate compiling Kotlin from this repository's real
// openapi.yaml / asyncapi.yaml / schemas/*.json — the thing T9-T14b each proved only
// with an ad-hoc local harness, deleted before their MR. This is that harness, kept
// and wired into CI (ci/build.gitlab-ci.yml's `contracts-codegen-compile` job) rather
// than reinvented per task.
//
// Composite build: `includeBuild`s platform-build's `build-logic` at a pinned ref —
// no published plugin-marker artefact exists (platform-build's own pipeline has never
// run a `publish` job: it is still INV-T-2845's bootstrap pipeline, not the T4
// templates, and MAVEN_PUBLISH is unset either way; see the MR). The CI job clones
// platform-build to $PLATFORM_BUILD_DIR at that pinned commit before invoking Gradle
// here; a local run without that env var falls back to a sibling checkout.
//
// One Gradle subproject per (service, generated-artefact) pair, generated below
// rather than hand-maintained: a service or event schema landing in
// platform-contracts (T9-T14b already did, repeatedly, mid-Wave-1) needs no change
// here to be covered next time this job runs.
//
// `resolveContractSource` (build-logic) resolves each `platform-contracts:<service>:<file>`
// coordinate as a Maven dependency (version `+`, its provisional default — no caller
// overrides it), so every module needs a real, dynamic-version-capable Maven
// repository to resolve against; there is no published one, so this script stages one
// from the real files on every configuration pass, under build/contracts-repo/.
//
// That staged layout mirrors each service directory's OWN relative position next to
// service-runtime/: platform-build's plugins then fully resolve the resulting
// document, INCLUDING the shared `../service-runtime/schemas/error-envelope.schema.json`
// $ref every service's openapi.yaml carries (API-20) — proven by this build actually
// compiling the generated `ErrorEnvelope` model (real fields: code, severity,
// disposition, remediationOwner, traceRef — not a stand-in). Do not flatten, copy, or
// inline that $ref's target to make resolution "work": if a future contract's
// cross-file $ref does not resolve this way, that is a platform-build plugin defect
// to report by exact coordinate and file, not something to route around here.

pluginManagement {
    val platformBuildDir =
        providers.environmentVariable("PLATFORM_BUILD_DIR").orNull
            ?: "../../platform-build" // local convenience default: a sibling checkout of platform-build
    includeBuild("$platformBuildDir/build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "contracts-codegen-compile"

val contractsRoot = rootDir.parentFile!! // platform-contracts checkout root (this directory's parent)
val stagingRoot = file("build/contracts-repo")
val modulesRoot = file("build/modules")
val version = "1.0.0"

fun classifierFor(relFile: String) = relFile.substringBeforeLast('.', relFile).replace('/', '-')

fun extensionFor(relFile: String) = relFile.substringAfterLast('.', "")

val serviceDirs =
    contractsRoot.listFiles { f ->
        f.isDirectory && File(f, "openapi.yaml").isFile && File(f, "asyncapi.yaml").isFile
    }!!.sortedBy { it.name }

val serverModules = mutableListOf<String>()
val clientModules = mutableListOf<String>()
val eventModules = mutableListOf<Pair<String, String>>() // (service, relative schema file)

for (svcDir in serviceDirs) {
    val svc = svcDir.name
    val versionDir = File(stagingRoot, "platform-contracts/$svc/$version")
    versionDir.mkdirs()
    svcDir.copyRecursively(versionDir, overwrite = true)

    fun publish(relFile: String) {
        val src = File(svcDir, relFile)
        if (!src.isFile) return
        src.copyTo(File(versionDir, "$svc-$version-${classifierFor(relFile)}.${extensionFor(relFile)}"), overwrite = true)
    }

    publish("openapi.yaml")
    publish("asyncapi.yaml")

    val schemaFiles = mutableListOf<String>()
    val schemasDir = File(svcDir, "schemas")
    if (schemasDir.isDirectory) {
        schemasDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.path }
            .forEach { f ->
                val rel = "schemas/" + f.relativeTo(schemasDir).path.replace('\\', '/')
                publish(rel)
                schemaFiles += rel
            }
    }

    // Sibling copy of service-runtime/, at the same relative offset from this staged
    // module as the real service-runtime/ sits next to the real <svc>/ (one level up
    // from the version directory): the mechanism that lets this service's own
    // openapi.yaml resolve its `../service-runtime/schemas/error-envelope...` $ref
    // inside the staged repo exactly as it does in the real checkout. See the file
    // header: this mirrors, it never inlines.
    if (svc != "service-runtime") {
        File(contractsRoot, "service-runtime")
            .copyRecursively(File(stagingRoot, "platform-contracts/$svc/service-runtime"), overwrite = true)
    }

    File(stagingRoot, "platform-contracts/$svc/maven-metadata.xml").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>platform-contracts</groupId>
          <artifactId>$svc</artifactId>
          <versioning>
            <latest>$version</latest>
            <release>$version</release>
            <versions><version>$version</version></versions>
            <lastUpdated>20260101000000</lastUpdated>
          </versioning>
        </metadata>
        """.trimIndent(),
    )
    File(versionDir, "$svc-$version.pom").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>platform-contracts</groupId>
          <artifactId>$svc</artifactId>
          <version>$version</version>
          <packaging>pom</packaging>
        </project>
        """.trimIndent(),
    )

    serverModules += svc
    clientModules += svc
    // The two shared envelopes are $ref targets (STK-46), not a domain/platform event
    // payload (gst.event-types' actual target) — same per-file exclusion
    // spectral/run-oasdiff-gate.sh uses (INV-T-2855, T11), not a blanket skip of
    // service-runtime/: T11 added real platform-event payloads there too
    // (audit-entry-recorded, connection-status-changed, job-run-recorded,
    // pack-readiness-acknowledged, policy-changed), which this covers like any other.
    val envelopeFiles = setOf("schemas/cloud-event-envelope.schema.json", "schemas/error-envelope.schema.json")
    schemaFiles.filterNot { it in envelopeFiles }.forEach { rel -> eventModules += svc to rel }
}

val repositoriesBlock =
    """
    repositories {
        maven { url = uri("${stagingRoot.absolutePath}") }
        mavenCentral()
    }
    """.trimIndent()

val dependenciesBlock =
    """
    dependencies {
        // Pinned to the same versions platform-build's own ContractGenerationFunctionalTest
        // uses against these plugins (build-logic/src/test/kotlin/gst/build/ContractGenerationFunctionalTest.kt).
        implementation("org.springframework.boot:spring-boot-starter-web:4.1.1")
        implementation("org.springframework.boot:spring-boot-starter-validation:4.1.1")
        implementation("tools.jackson.module:jackson-module-kotlin:3.2.3")
        implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    }
    """.trimIndent()

// gst.contract-server / -client / -event-types derive their generated code's package
// name from `project.name` when it does not end in one of TPL-01's five real module
// suffixes (`serviceNameOf`, gst/build/ServiceModuleBoundary.kt) — which none of this
// self-test's module names do, by construction (they are not real TPL-01 modules).
// That makes `project.name` itself the package segment, so it must be a valid Kotlin
// identifier: no hyphens, including any already in a service's own directory name
// (e.g. `console-bff`). This is cosmetic to this self-test only, not a rename of
// anything real.
fun projectSafe(s: String) = s.replace('-', '_').replace('.', '_')

fun declareModule(
    name: String,
    body: String,
) {
    val safeName = projectSafe(name)
    val dir = File(modulesRoot, safeName)
    dir.mkdirs()
    File(dir, "build.gradle.kts").writeText(body)
    include(":$safeName")
    project(":$safeName").projectDir = dir
}

for (svc in serverModules) {
    declareModule(
        "$svc-server",
        """
        plugins { id("gst.contract-server") }
        $repositoriesBlock
        $dependenciesBlock
        contractServer { generatorSource("platform-contracts:$svc:openapi.yaml") }
        """.trimIndent() + "\n",
    )
}

for (svc in clientModules) {
    declareModule(
        "$svc-client",
        """
        plugins { id("gst.contract-client") }
        $repositoriesBlock
        $dependenciesBlock
        contractClient { generatorSource("platform-contracts:$svc:openapi.yaml") }
        """.trimIndent() + "\n",
    )
}

for ((svc, rel) in eventModules) {
    val baseName =
        rel
            .removePrefix("schemas/")
            .removeSuffix(".schema.json")
            .removeSuffix(".json")
            .replace('/', '-')
    declareModule(
        "$svc-event-$baseName",
        """
        plugins { id("gst.event-types") }
        $repositoriesBlock
        $dependenciesBlock
        eventTypes { generatorSource("platform-contracts:$svc:$rel") }
        """.trimIndent() + "\n",
    )
}
