// catalogue/settings.gradle.kts — INV-T-2851 (T7).
//
// Necessary companion to catalogue/build.gradle.kts (this task's own declared scope):
// a Gradle build needs a settings file to have subprojects at all, the same way
// platform-build's root settings.gradle.kts is inseparable from build-logic/
// (build-logic/settings.gradle.kts pattern, mirrored one level down here).
//
// platform-contracts' own root settings.gradle.kts declares no subprojects (that
// repository has no Kotlin source outside this directory) — catalogue/ is therefore
// its own independent Gradle build, invoked as `gradle -p catalogue <task>`, exactly
// as ci/stages.gitlab-ci.yml's `GRADLE_PROJECT_DIR` variable already anticipates
// ("your Gradle wrapper root isn't the repository root"); platform-build's own
// .gitlab-ci.yml sets that same variable to `build-logic` for the same reason.

pluginManagement {
    includeBuild("generator") // provides gst.catalogue-generator (STK-61, CS-13)
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// STK-72 (INV-T-4299, coordinator ruling INV-Q-4322): the settings classpath is locked,
// with its state in the committed settings-gradle.lockfile, as in platform-libs. The foojay
// toolchain resolver is put on that classpath here, not through a settings `plugins {}`
// block: Gradle resolves settings plugin requests outside the locked `classpath`
// configuration, so the plugin would go unlocked.
buildscript {
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.gradle.toolchains:foojay-resolver:1.0.0")
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

apply(plugin = "org.gradle.toolchains.foojay-resolver-convention")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "platform-contracts-catalogue"

include("gst-catalogue")
