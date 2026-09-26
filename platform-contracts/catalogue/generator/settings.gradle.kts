// catalogue/generator/settings.gradle.kts — INV-T-2851 (T7).
//
// An independent build (a settings file makes it one, exactly like
// platform-build/build-logic/settings.gradle.kts): included into ../settings.gradle.kts
// via `pluginManagement.includeBuild("generator")` so `gst-catalogue/build.gradle.kts`
// can `apply(plugin = "gst.catalogue-generator")`.

rootProject.name = "gst-catalogue-generator"
