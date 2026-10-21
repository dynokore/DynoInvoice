// build.gradle.kts — svc-masterdata root (TPL-01, STK-72; INV-T-4942).
//
// No buildable code sits at the root: every module is its own Gradle project. This file holds what every module shares
// (STK-72 locking) and the repository gates: acKeyCoverage (ac-keys.tsv) and operationsTable (operations.tsv).

// STK-72: every resolvable configuration of every project is locked in strict mode, the plugin classpath of the root
// and of each module too (buildscript-gradle.lockfile), and the settings classpath (gradle/settings-gradle.lockfile).
buildscript {
    dependencies {
        // The Spring Boot Gradle plugin brings Jackson 3 3.1.5 (HIGH CVEs, fixed in 3.1.7); platform-build's build-logic
        // forces 3.1.7 for the modules, this root classpath needs the same floor.
        constraints {
            classpath("tools.jackson.core:jackson-core:3.1.7")
            classpath("tools.jackson.core:jackson-databind:3.1.7")
        }
    }
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
    group = "com.dynokore.gst.masterdata"

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

// Repository gates (INV-T-4942).
apply(from = "gradle/gates/ac-key-coverage.gradle.kts")
