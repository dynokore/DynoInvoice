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
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
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
    dependsOn("verifyFlatModels")
}

// INV-T-17921: CertifySandboxRequest must generate a data class (a oneOf made it a sealed
// interface with branch classes that dropped the members). Fails closed: a missing file, a
// missing text or a present `sealed interface` fails the build and names the file.
val flatModelFiles =
    mapOf(
        "tenancy_server" to
            "build/modules/tenancy_server/build/generated/contract-server/src/main/kotlin/" +
            "com/dynokore/gst/tenancy_server/contract/model/CertifySandboxRequest.kt",
        "tenancy_client" to
            "build/modules/tenancy_client/build/generated/contract-client/src/main/kotlin/" +
            "com/dynokore/gst/tenancy_client/adapters/clients/model/CertifySandboxRequest.kt",
    )

tasks.register("verifyFlatModels") {
    group = "verification"
    description = "Fails unless the generated server and client CertifySandboxRequest are flat data classes (INV-T-17921)."
    dependsOn(flatModelFiles.keys.map { ":$it:compileKotlin" })
    val files = flatModelFiles.values.map { layout.projectDirectory.file(it).asFile }
    doLast {
        val problems = mutableListOf<String>()
        for (f in files) {
            if (!f.isFile) {
                problems += "${f.path}: generated file is missing"
                continue
            }
            val text = f.readText()
            // The generator writes `data class X(` in the server file and `data class X (` in the
            // client file: the check asserts the shape, not the whitespace (INV-T-17921 ruling).
            val dataClass = Regex("""data class CertifySandboxRequest\s*\(""")
            if (!dataClass.containsMatchIn(text)) problems += "${f.path}: missing text 'data class CertifySandboxRequest('"
            for (required in listOf("val outcome", "val suiteVersion", "val failedScenarios")) {
                if (!text.contains(required)) problems += "${f.path}: missing text '$required'"
            }
            if (text.contains("sealed interface")) problems += "${f.path}: contains 'sealed interface'"
        }
        if (problems.isNotEmpty()) throw GradleException("verifyFlatModels failed:\n" + problems.joinToString("\n"))
    }
}
