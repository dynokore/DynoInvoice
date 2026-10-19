// build.gradle.kts — svc-rules root (TPL-01, STK-72; INV-T-4965).
//
// No code sits at the repository root: the five architectural modules (rules-contract, -domain, -application,
// -adapters, -bootstrap; ARC-10) are the Gradle projects. This file holds what they share: dependency locking and
// the two repository gates (operations.tsv, ac-keys.tsv) in gradle/gates.gradle.kts.

// STK-72 (INV-T-4297, coordinator ruling INV-Q-4367): every resolvable configuration of every project is locked in
// strict mode, the plugin classpath of the root and of each module too (buildscript-gradle.lockfile) and the settings
// classpath (gradle/settings-gradle.lockfile). Every downloaded artefact is verified against
// gradle/verification-metadata.xml. Refresh after a dependency change: README.md, "Dependency locking and verification".
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

allprojects {
    group = "com.dynokore.gst" // layering standard §2.3

    buildscript.configurations.named("classpath") {
        resolutionStrategy.activateDependencyLocking()
    }
    if (this != rootProject) {
        // The root's own buildscript lock mode is set (and already final) in its buildscript block above.
        buildscript.dependencyLocking.lockMode.set(LockMode.STRICT)
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

// Resolves every resolvable configuration of every project, so one
// `./gradlew resolveAndLockAll --write-locks` writes every gradle.lockfile.
allprojects {
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

apply(from = "gradle/gates.gradle.kts")
