// build.gradle.kts — INV-T-2849 (T5)
//
// Packaging only (see settings.gradle.kts). `bundleContracts` zips every
// directory's published contract — openapi.yaml, asyncapi.yaml, schemas/**
// — plus the shared service-runtime/ directory, into one versioned archive:
// the "versioned contracts bundle per train" of this task's last done-when
// item. ci/.gitlab-ci.yml's `bundle-publish` job runs this and pushes the
// result to the generic package registry, main/tag pipelines only (STK-71).
//
// Per-service Maven modules that a service's own `gst.contract-server` /
// `gst.contract-client` plugin (TPL-01) resolves via
// `generatorSource("platform-contracts:<service>:openapi.yaml")` are a
// follow-up: those plugins are not built yet (a later Keel task), so nothing
// could consume per-service artefacts today either. Named in the INV-T-2849
// MR, not built here (AGT-04: the provisional default is a bundle CI can
// publish now, not a shape only a not-yet-existing consumer would use).

val contractDirs =
    layout.projectDirectory.asFileTree.matching {
        include("*/openapi.yaml", "*/asyncapi.yaml", "*/schemas/**", "*/CODEOWNERS")
    }

// The train version: a real release train names its own tag (STK-71); locally,
// and on any branch before a train is cut, this is a clearly-marked dev build,
// never silently "1.0.0" (AGT-24 — an unreleased bundle says so in its own name).
val trainVersion =
    (providers.gradleProperty("trainVersion").orNull
        ?: providers.environmentVariable("CI_COMMIT_TAG").orNull)
        ?: "0.0.0-dev+${providers.environmentVariable("CI_COMMIT_SHORT_SHA").getOrElse("local")}"

tasks.register<Zip>("bundleContracts") {
    group = "distribution"
    description = "Packages every service's openapi.yaml/asyncapi.yaml/schemas and service-runtime's shared schemas into one versioned train bundle."
    archiveBaseName.set("platform-contracts")
    archiveVersion.set(trainVersion)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(contractDirs) {
        // Preserve each file's own directory (<service>/openapi.yaml, …) inside the archive.
        eachFile { relativePath = RelativePath(true, *relativePath.segments) }
    }
    from("CODEOWNERS")
    from("README.md")
    includeEmptyDirs = false
}

tasks.register("printTrainVersion") {
    group = "help"
    doLast { println(trainVersion) }
}

tasks.register("check") {
    group = "verification"
    description = "Placeholder aggregate task so CI's uniform `gradle check` invocation (parity with every Kotlin repository) has something to depend on."
}
