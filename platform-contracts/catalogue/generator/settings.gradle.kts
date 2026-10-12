// catalogue/generator/settings.gradle.kts — INV-T-2851 (T7).
//
// An independent build (a settings file makes it one, exactly like
// platform-build/build-logic/settings.gradle.kts): included into ../settings.gradle.kts
// via `pluginManagement.includeBuild("generator")` so `gst-catalogue/build.gradle.kts`
// can `apply(plugin = "gst.catalogue-generator")`.

// STK-72 (INV-T-4299, coordinator ruling INV-Q-4322): the settings classpath is locked,
// with its state in the committed settings-gradle.lockfile, as in platform-libs. The file is
// named explicitly: by default Gradle writes an included build's settings lock state to the
// including build's catalogue/settings-gradle.lockfile, overwriting that build's own state.
buildscript {
    dependencyLocking {
        lockFile.set(file("settings-gradle.lockfile"))
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

rootProject.name = "gst-catalogue-generator"
