// build.gradle.kts — svc-access root (TPL-01, STK-72; INV-T-4934).
//
// No buildable code sits at the root: every module is its own Gradle project. This file holds what every module shares
// (STK-72 locking) and the repository gates: acKeyCoverage (ac-keys.tsv) and operationsTable (operations.tsv).

// STK-72: every resolvable configuration of every project is locked in strict mode, the plugin classpath of the root
// and of each module too (buildscript-gradle.lockfile), and the settings classpath (gradle/settings-gradle.lockfile).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

plugins {
    base // root `check` and `build` lifecycle tasks, which the repository gates hang on
    // One declaration with the version for every module (Gradle loads the plugin once, not per subproject).
    id("org.springframework.boot") version "4.1.1" apply false
}

allprojects {
    group = "com.dynokore.gst.access"

    buildscript.configurations.named("classpath") {
        resolutionStrategy.activateDependencyLocking()
    }
    if (this != rootProject) {
        buildscript.dependencyLocking.lockMode.set(LockMode.STRICT)
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

// Resolves every resolvable configuration of every project, so one `./gradlew resolveAndLockAll --write-locks`
// writes every gradle.lockfile.
allprojects {
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

// Repository gates (INV-T-4934).
apply(from = "gradle/gates/ac-key-coverage.gradle.kts")
