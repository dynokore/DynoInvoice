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
//
// `gst.event-types` (unlike `gst.contract-server`/`-client`, which feed the resolved
// file straight to the generator) never opens the resolved schema file where it
// lands: `wrapEventSchemaAsOpenApi` (build-logic) reads it and re-embeds its raw
// content — `$id` and all — into a NEW wrapped document under that Gradle module's
// OWN `build/generated/event-types-openapi/`, which starts out with none of the
// schema's real siblings. A relative $ref to a same-directory sibling
// (`obligation-register-entry.schema.json`'s `./lifecycle-states.schema.json`), and
// even a schema's own `$id`-qualified internal `#/$defs/...` refs once wrapped
// (`lifecycle-states.schema.json` referencing its own `$defs`), are then resolved by
// the parser against THAT directory, one file at a time on a failed absolute-URL
// fetch of the embedded `$id` — never against wherever Maven staged the original.
// Confirmed by re-running the generator: it fails naming that exact path. So each
// event module's own generated build.gradle.kts (below) stages its WHOLE service's
// `schemas/` directory there too — as a whole, preserving members' relative (sibling)
// layout — never inlining one $ref's target in its place.
//
// That fixes the sibling-file gap (review, MR !13): `obligation-register-entry`'s
// `./lifecycle-states.schema.json` $ref now finds a real file, same as
// `../service-runtime/...` already did. It does NOT fully green two of the ~240
// generated modules (`submission-event-lifecycle-states`,
// `submission-event-obligation-register-entry`): once the sibling file is found and
// loaded, resolving `lifecycle-states.schema.json`'s OWN `$id`-qualified internal
// `#/$defs/...` refs (it defines and cross-references `documentLevelState` /
// `versionLevelState` / `lifecycleState` / `stateCatalogueEntry` on itself) throws
// `NullPointerException: root is null` in swagger-parser's `ReferenceVisitor.findAnchor`
// — reproduced with the file well-formed and the ref present (confirmed: `$defs`
// contains exactly those four keys). This is a SEPARATE platform-build defect from
// the sibling-staging gap above: embedding a schema that carries its own top-level
// `$id` into `components.schemas` (`wrapEventSchemaAsOpenApi`) changes how OpenAPI
// 3.1 scopes `$ref` resolution for that embedded subtree, and nothing in
// platform-contracts can supply the "already-resolved document" the parser then
// expects — stripping or rewriting the committed schema's `$id` to dodge this would
// mask the defect for THIS harness while leaving it for every real consumer (the
// same principle as the hyphen defect above): reported instead, not routed around.

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

// STK-72 (INV-T-4299, coordinator ruling INV-Q-4322): the settings classpath is locked in
// strict mode, as in platform-libs. Its state is kept in gradle/settings-gradle.lockfile:
// Gradle writes the included build-logic's settings lock state to this build's default
// settings-gradle.lockfile, which would overwrite this build's own state (the same Gradle
// behaviour catalogue/generator/settings.gradle.kts works around).
buildscript {
    dependencyLocking {
        lockFile.set(file("gradle/settings-gradle.lockfile"))
        lockMode.set(LockMode.STRICT)
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

rootProject.name = "contracts-codegen-compile"

val contractsRoot = rootDir.parentFile!! // platform-contracts checkout root (this directory's parent)
val stagingRoot = file("build/contracts-repo")
val modulesRoot = file("build/modules")

// STK-72 (INV-T-4299, coordinator ruling INV-Q-4322 (2)): the generated modules live under
// the git-ignored build/, so each one's lock state is kept in one committed file,
// gradle/locks/<module>.lockfile. A module without one (a new contract artefact) fails here,
// and so does a lock file whose module no longer exists. Refresh: see README.md.
val locksRoot = file("gradle/locks")
val writingLocks = gradle.startParameter.isWriteDependencyLocks
val declaredModules = mutableSetOf<String>()
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

// The platform-contracts group resolves only from the staging repository (exclusiveContent),
// never from a remote one: that is what makes its exclusion from locking and verification
// safe (STK-72, coordinator ruling INV-Q-4322 (3)). Every other group comes from Maven Central.
val repositoriesBlock =
    """
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("${stagingRoot.absolutePath}") } }
            filter { includeGroup("platform-contracts") }
        }
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
// (e.g. `console-bff`). Sanitizing it here (self-test module names only) works around
// the symptom for this harness, but `serviceNameOf` matches a REAL TPL-01 module
// (`console-bff-contract`, etc.) by suffix and returns the hyphenated remainder
// unchanged, so the real `console-bff` service will hit this same invalid-package-
// segment defect the first time any of these three plugins runs against it — this is
// a platform-build defect (review, INV-T-2850 MR !13), tracked as an INV-T-2847
// follow-up (T3 owns `serviceNameOf` and its three callers), not something this
// self-test's sanitization settles.
fun projectSafe(s: String) = s.replace('-', '_').replace('.', '_')

// Appended to every generated module's build script. Strict locking of every configuration
// into the module's committed lock file. The same-commit platform-contracts:* coordinates
// (staged into build/contracts-repo with version '+', never downloaded) are left out: there
// is no remote artefact to pin (INV-Q-4322 (3)); every external artefact stays locked. The
// module's plugin classpath must stay empty: gst.contract-server/-client/-event-types come
// from the root build script's classpath (codegen-self-test/buildscript-gradle.lockfile),
// so a module that resolves a plugin classpath of its own fails instead of going unlocked.
fun lockingBlock(lockFile: File) =
    """

    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
        lockFile.set(file("${lockFile.absolutePath}"))
        ignoredDependencies.add("platform-contracts:*")
    }
    check(buildscript.configurations.getByName("classpath").dependencies.isEmpty()) {
        "STK-72: a generated module's plugin classpath must come from codegen-self-test/build.gradle.kts (locked there)"
    }
    """.trimIndent() + "\n"

fun declareModule(
    name: String,
    body: String,
) {
    val safeName = projectSafe(name)
    val dir = File(modulesRoot, safeName)
    dir.mkdirs()
    val lockFile = File(locksRoot, "$safeName.lockfile")
    if (!lockFile.isFile && !writingLocks) {
        throw GradleException(
            "codegen-self-test: no committed lock file ${lockFile.relativeTo(rootDir)} for generated module " +
                "'$safeName' (a new contract artefact?). Write it with " +
                "`gradle -p codegen-self-test resolveAndLockAll compileAllContracts --write-locks`, " +
                "add its artefacts to gradle/verification-metadata.xml (README.md) and commit both (STK-72).",
        )
    }
    declaredModules += lockFile.name
    File(dir, "build.gradle.kts").writeText(body + lockingBlock(lockFile))
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
    // Absolute path to this service's real schemas/ directory (not the staged Maven
    // repo): see the file header. Interpolated as a literal string into the generated
    // module's own build.gradle.kts below.
    val svcSchemasDir = File(contractsRoot, "$svc/schemas").absolutePath
    declareModule(
        "$svc-event-$baseName",
        """
        plugins { id("gst.event-types") }
        $repositoriesBlock
        $dependenciesBlock
        eventTypes { generatorSource("platform-contracts:$svc:$rel") }

        // Stage this service's whole schemas/ directory into gst.event-types' own
        // wrapped-document directory too (see settings.gradle.kts file header): a
        // relative or ${'$'}id-qualified ${'$'}ref in the wrapped schema is resolved
        // against THAT directory, not wherever Maven staged the original file. Must
        // run after wrapEventSchema writes its own openapi.yaml there first (explicit
        // dependsOn: two tasks writing into the same directory need a declared
        // order, not an accidental one).
        val stageSiblingSchemas by tasks.registering(Copy::class) {
            dependsOn("wrapEventSchema")
            from("$svcSchemasDir")
            into(layout.buildDirectory.dir("generated/event-types-openapi"))
        }
        tasks.named("generateEventTypes") { dependsOn(stageSiblingSchemas) }
        """.trimIndent() + "\n",
    )
}

// A lock file left behind by a module that no longer exists is stale state: fail, so the
// committed gradle/locks/ always matches the generated modules exactly.
val staleLocks =
    (locksRoot.listFiles { f -> f.isFile && f.name.endsWith(".lockfile") }?.map { it.name } ?: emptyList())
        .filterNot { it in declaredModules }
        .sorted()
if (staleLocks.isNotEmpty()) {
    if (writingLocks) {
        staleLocks.forEach { File(locksRoot, it).delete() }
    } else {
        throw GradleException("codegen-self-test: lock files without a generated module in gradle/locks/: $staleLocks (STK-72); delete them or rerun with --write-locks")
    }
}
