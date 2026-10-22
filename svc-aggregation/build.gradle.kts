// The plugins of every module are put on one class loader here (apply false), so a Gradle service shared by two of
// them (Spotless' task service) is one class: without this the Spring Boot plugin of aggregation-bootstrap and the
// gst.* plugins of its siblings load in different class loaders and spotless cannot create its tasks.
plugins {
    id("gst.kotlin-base") apply false
    id("gst.domain") apply false
    id("gst.application") apply false
    id("gst.adapters") apply false
    id("gst.bootstrap") apply false
    id("gst.contract-server") apply false
    id("gst.jooq-codegen") apply false
    id("org.springframework.boot") apply false
}

// build.gradle.kts — svc-aggregation root (TPL-01; INV-T-8451). Only what every module shares.
allprojects {
    group = "com.dynokore.gst.aggregation"
    version = "0.1.0"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

// STK-72: every resolvable configuration of every project is locked in strict mode, the plugin classpath of the root and
// of each module too (buildscript-gradle.lockfile). A configuration without lock state, a resolution outside the lock
// files or a deleted lock file fails the build. Every downloaded artefact is verified against
// gradle/verification-metadata.xml in strict mode. Refresh after a dependency change: README.md.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

allprojects {
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

// Resolves every resolvable configuration of every project, so one `gradle resolveAndLockAll --write-locks` writes
// every gradle.lockfile; the build script and settings classpaths are resolved while the build is configured.
allprojects {
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}
