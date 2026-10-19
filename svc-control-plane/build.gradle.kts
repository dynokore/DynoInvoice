// Root build: entry points and what every project shares (TPL-01).

// STK-72 (INV-T-4300): every resolvable configuration of every project is locked in strict mode, the plugin classpath of
// the root and of each module too (buildscript-gradle.lockfile) and the settings classpath (gradle/settings-gradle.lockfile).
// A configuration without lock state, a resolution outside the lock files or a deleted lock file fails the build. Every
// downloaded artefact is verified against gradle/verification-metadata.xml in strict mode. Refresh after a dependency
// change: README.md, "Dependency locking and verification".
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

plugins {
    base
    // On the root classpath once, so sibling projects share one Spotless build service (Gradle: a plugin applied to two
    // sibling projects that uses a shared build service).
    id("gst.kotlin-base") apply false
}

allprojects {
    group = "com.dynokore.gst.controlplane"

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
    // Resolves every resolvable configuration, so one `./gradlew resolveAndLockAll --write-locks` writes every lock file.
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

// `./gradlew acKeyCoverage` and `./gradlew operationsGate` run the repository gates of the acceptance project.
tasks.register("acKeyCoverage") { dependsOn(":acceptance:acKeyCoverage") }
tasks.register("operationsGate") { dependsOn(":acceptance:operationsGate") }
