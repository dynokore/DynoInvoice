// build.gradle.kts — svc-tenancy root (INV-T-4925; TPL-01, STK-72).
//
// No buildable code sits at the repository root: the five architectural modules (ARC-10) and the test-only
// `acceptance` project are Gradle projects of their own. This file holds what they share: dependency locking,
// the repository gates (gradle/gates.gradle.kts) and the group.

// STK-72 (coordinator ruling INV-Q-4367): every resolvable configuration of every project is locked in strict
// mode, the plugin classpath of the root and of each module too (buildscript-gradle.lockfile), and the settings
// classpath (gradle/settings-gradle.lockfile, settings.gradle.kts). Every downloaded artefact is verified against
// gradle/verification-metadata.xml. Refresh after a dependency change: README.md, "Dependency locking and
// verification".
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

allprojects {
    group = "com.dynokore.gst"

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

// Resolves every resolvable configuration of every project, so one
// `./gradlew resolveAndLockAll --write-locks` writes every gradle.lockfile; the build script and settings
// classpaths are resolved while the build is configured and are written in the same run.
allprojects {
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

// Every module runs JUnit Jupiter (STK-38); the convention plugins leave the test framework to the module.
subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
