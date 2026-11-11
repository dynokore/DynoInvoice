// acceptance (TPL-01): test-only; scenario tests traced to Keel criteria by key.
plugins {
    id("gst.kotlin-base")
}

dependencies {
    testImplementation(platform(libs.springBoot.dependencies))
    testImplementation(platform(libs.jackson.bom))
    testImplementation(project(":console-bff-contract"))
    testImplementation(project(":console-bff-domain"))
    testImplementation(project(":console-bff-adapters"))
    testImplementation(libs.springBoot.starterWeb)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.gstCatalogue)
    testImplementation(project(":console-bff-bootstrap"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
    constraints {
        testImplementation(libs.tomcat.embedCore)
        testImplementation(libs.tomcat.embedEl)
        testImplementation(libs.tomcat.embedWebsocket)
    }
}

// ArchitectureTest reads the generated contract sources.
tasks.withType<Test>().configureEach {
    dependsOn(":console-bff-contract:generateContractServer")
}

// OperationsGateTest reads the pinned contract documents staged by console-bff-contract and the table at the root.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":console-bff-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/console-bff/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
    systemProperty("gst.root.dir", rootProject.projectDir.path)
    systemProperty("gst.projects", rootProject.subprojects.map { it.name }.joinToString(","))
}

// acKeyCoverage (INV-T-14438): fails closed over the JUnit XML of every module's test tasks; see AcKeyCoverage.kt.
tasks.named<Test>("test") {
    exclude("**/AcKeyCoverageGate*", "**/SqlPlacementResultGate*")
}

val acKeyCoverage =
    tasks.register<Test>("acKeyCoverage") {
        group = "verification"
        description =
            "Every row of ac-keys.tsv with a wave up to RUN_WAVE has an executed test named by its key, and no test names a key outside the table."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        include("**/AcKeyCoverageGate*")
        // after every test task of the build (all modules), whose XML it reads
        dependsOn(rootProject.subprojects.map { "${it.path}:test" })
        outputs.upToDateWhen { false }
    }

tasks.named("check") { dependsOn(acKeyCoverage) }

// CI's unit-test job runs only `test`: finalize it with the gate so the gate runs there and its failure fails the job.
tasks.named<Test>("test") { finalizedBy(acKeyCoverage) }

// sqlPlacementGate (INV-T-14438): after verifySqlPlacement of each of the five modules (listed by equality in
// SqlPlacement.kt), fails closed on a missing module, a missing result file or any finding; no exemption.
val sqlPlacementGate =
    tasks.register<Test>("sqlPlacementGate") {
        group = "verification"
        description = "verifySqlPlacement reports zero findings in each of the five modules (INV-Q-7422)."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        include("**/SqlPlacementResultGate*")
        listOf(
            "console-bff-domain",
            "console-bff-application",
            "console-bff-adapters",
            "console-bff-bootstrap",
            "console-bff-contract",
        ).forEach { module ->
            dependsOn(rootProject.subprojects.filter { it.name == module }.map { "${it.path}:verifySqlPlacement" })
        }
        outputs.upToDateWhen { false }
        // The outcome of each verify task on this build: a disabled or skipped task leaves an earlier build's file.
        doFirst {
            val outcomes =
                rootProject.subprojects
                    .map {
                        it.name to
                            it.tasks.findByName(
                                "verifySqlPlacement",
                            )
                    }.joinToString(",") { (name, task) ->
                        val state = task?.state
                        val outcome =
                            when {
                                state == null -> "MISSING"
                                state.failure != null -> "FAILED"
                                !state.executed -> "NOT-RUN"
                                state.skipMessage == null -> "EXECUTED"
                                else -> state.skipMessage!!
                            }
                        "$name=$outcome"
                    }
            systemProperty("gst.sqlplacement.outcomes", outcomes)
        }
    }

tasks.named("check") { dependsOn(sqlPlacementGate) }

// CI's unit-test job runs only `test` and reports its JUnit XML: finalize it with the gate (as acKeyCoverage above), so
// the gate's case runs there and shows in the test report.
tasks.named<Test>("test") { finalizedBy(sqlPlacementGate) }
