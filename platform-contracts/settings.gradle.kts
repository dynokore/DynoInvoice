// settings.gradle.kts — INV-T-2849 (T5)
//
// platform-contracts has no Kotlin source: it is contract text (OpenAPI,
// AsyncAPI, JSON Schema) linted and diffed by Spectral/oasdiff/the AsyncAPI
// CLI (all run directly in CI, ci/.gitlab-ci.yml — no Gradle plugin wraps
// them). The one Gradle concern here is packaging: build.gradle.kts's
// `bundleContracts` task zips every directory's contracts into the versioned
// per-train bundle the last done-when item asks for. No wrapper is
// committed; CI invokes the pinned `gradle` binary directly from
// platform-build's `$GRADLE_IMAGE` (ci/stages.gitlab-ci.yml) — there is no
// dependency resolution here for a wrapper's verification-metadata.xml to
// protect.
rootProject.name = "platform-contracts"
