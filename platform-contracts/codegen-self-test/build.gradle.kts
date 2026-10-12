// build.gradle.kts — INV-T-2850 follow-up.
//
// One task the CI job (and a local run) invokes: compile every generated module.
// The modules themselves (one per service x {server, client} plus one per event
// schema) are declared dynamically in settings.gradle.kts; this just aggregates
// their compileKotlin tasks so nothing here needs updating as services/events change.

// STK-72 (INV-T-4299, coordinator rulings INV-Q-4322 and INV-Q-4327): the three
// platform-build code-generation plugins are put on THIS build script's classpath (apply
// false), so their classpath, build-logic's external dependencies included, is locked once
// in codegen-self-test/buildscript-gradle.lockfile; the generated modules inherit it and
// resolve no plugin classpath of their own (settings.gradle.kts checks that). Each generated
// module locks its own configurations into gradle/locks/<module>.lockfile (settings.gradle.kts).
// Every downloaded artefact is verified against gradle/verification-metadata.xml (strict).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    id("gst.contract-server") apply false
    id("gst.contract-client") apply false
    id("gst.event-types") apply false
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

allprojects {
    // Resolves every resolvable configuration (build script classpath included), so one
    // `gradle resolveAndLockAll --write-locks` writes every lock file of this build.
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
            buildscript.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

tasks.register("compileAllContracts") {
    group = "verification"
    description =
        "Generates and compiles every service's contract-server, contract-client and " +
            "event-types Kotlin (INV-T-2850 follow-up; T9-T14b's ad-hoc proof, made permanent)."
    dependsOn(subprojects.map { "${it.path}:compileKotlin" })
}
