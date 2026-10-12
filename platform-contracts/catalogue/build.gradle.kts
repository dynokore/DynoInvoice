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

// STK-72 (INV-T-4299, coordinator rulings INV-Q-4322 and INV-Q-4327): dependency locking
// covers the plugin classpath too. This root's and each module's build script classpath
// (the plugins its `plugins {}` block resolves) are locked in buildscript-gradle.lockfile
// next to that build script, and every configuration of every project is locked in strict
// mode (gradle.lockfile per module), so a resolution outside the lock files fails the
// build. generator/ is an included build and locks itself (generator/build.gradle.kts).
// Every downloaded artefact, generator/'s included, is verified against
// gradle/verification-metadata.xml (strict). Refresh: see README.md.
buildscript {
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    base
}

subprojects {
    // The root's own build script handler is set in its buildscript {} block above.
    buildscript.dependencyLocking.lockMode.set(LockMode.STRICT)
}

allprojects {
    buildscript.configurations.named("classpath") {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

allprojects {
    // Resolves every resolvable configuration (build script classpath included), so one
    // `gradle resolveAndLockAll --write-locks` writes every lock file of this build.
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
            buildscript.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("generator").task(":test"))
}
