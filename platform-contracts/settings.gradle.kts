// settings.gradle.kts — INV-T-2849 (T5)
//
// platform-contracts has no Kotlin source: it is contract text (OpenAPI,
// AsyncAPI, JSON Schema) linted and diffed by Spectral/oasdiff/the AsyncAPI
// CLI (all run directly in CI, ci/.gitlab-ci.yml — no Gradle plugin wraps
// them). The one Gradle concern here is packaging: build.gradle.kts's
// `bundleContracts` task zips every directory's contracts into the versioned
// per-train bundle the last done-when item asks for. No wrapper is
// committed; CI invokes the pinned `gradle` binary directly from
// platform-build's `$GRADLE_IMAGE` (ci/stages.gitlab-ci.yml).
//
// STK-72 (INV-T-4299, coordinator ruling INV-Q-4322): the settings classpath is locked
// (settings-gradle.lockfile, as in platform-libs) and gradle/verification-metadata.xml
// verifies any artefact this build downloads, in strict mode.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

rootProject.name = "platform-contracts"
