// catalogue/build.gradle.kts — INV-T-2851 (T7), STK-61.
//
// Root of the `catalogue` build (settings.gradle.kts): a separate Gradle build from
// platform-contracts' repository root (which has no Kotlin source of its own, only
// the per-train contracts bundle, build.gradle.kts at the repository root). CI builds
// it with `GRADLE_PROJECT_DIR=catalogue` (ci/stages.gitlab-ci.yml's own convention,
// already used by platform-build itself for build-logic/).
//
// No product code lives at this level: `gst-catalogue/` is the one real module.
// `generator/` is an included build (pluginManagement, settings.gradle.kts), not a
// subproject, so it does not appear under `include(...)` here or in `gradle projects`.
// Nothing to configure at the root beyond what settings.gradle.kts already declares;
// `gradle build`/`gradle check` at this directory delegate to `gst-catalogue`'s own
// tasks, same as any other multi-project Gradle build with a single leaf project.
//
// One exception: `generator/`'s own unit tests (ErrorCodeYamlParserTest,
// StabilityCheckerTest, BlockDispositionCheckerTest, …) prove the generator and its
// two acceptance checks themselves — a `pluginManagement.includeBuild`'d build's own
// tasks are not run just because the outer build's same-named task is, so `check`
// here explicitly depends on `generator`'s `test` task instead of silently skipping it.

plugins {
    base
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("generator").task(":test"))
}
