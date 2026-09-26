// build.gradle.kts — platform-libs root (STK-60, FR-L.6; INV-T-2860 / T16).
//
// No buildable code sits at the repository root: every module below is its own
// Gradle project, one directory per module (README.md). This file only holds
// what every module shares, so a module's own build.gradle.kts stays a plain
// declaration of its layer, its api/impl split and its dependencies.

allprojects {
    group = "com.dynokore.platform" // layering standard §2.3
}

subprojects {
    // STK-72: dependency locking is active on every resolvable configuration, the
    // same as every platform-build module. Lock files live per module, refreshed
    // with `./gradlew :<module>:dependencies --write-locks` when a dependency
    // changes; never hand-edited.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencyLocking {
            lockAllConfigurations()
        }
    }
}
