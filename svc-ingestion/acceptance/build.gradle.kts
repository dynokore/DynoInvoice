// acceptance (TPL-01): test-only; scenario tests traced to Keel criteria by key.
plugins {
    id("gst.kotlin-base")
}

dependencies {
    testImplementation(platform(libs.springBoot.dependencies))
    testImplementation(project(":ingestion-contract"))
    testImplementation(project(":ingestion-domain"))
    testImplementation(project(":ingestion-adapters"))
    testImplementation(libs.springBoot.starterWeb)
    testImplementation(libs.snakeyaml)
    testImplementation(project(":ingestion-bootstrap"))
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
}

// ArchitectureTest reads the generated contract sources.
tasks.withType<Test>().configureEach {
    dependsOn(":ingestion-contract:generateContractServer")
}

// OperationsGateTest reads the pinned contract documents staged by ingestion-contract and the table at the root.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":ingestion-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/ingestion/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
    systemProperty("gst.root.dir", rootProject.projectDir.path)
    systemProperty("gst.projects", rootProject.subprojects.map { it.name }.joinToString(","))
}

// acKeyCoverage (INV-T-5968): fails closed over the JUnit XML of every module's test tasks; see AcKeyCoverage.kt.
tasks.named<Test>("test") {
    exclude("**/AcKeyCoverageGate*", "**/SqlPlacementResultGate*")
}

val acKeyCoverage =
    tasks.register<Test>("acKeyCoverage") {
        group = "verification"
        description =
            "Every wave 3 row of ac-keys.tsv has an executed test named by its key, and no test names a key outside the table."
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

// sqlPlacementGate (INV-T-11026): after verifySqlPlacement of each of the five modules (listed by equality in
// SqlPlacement.kt), fails closed on a missing module, a missing result file or any finding; no exemption.
val sqlPlacementGate =
    tasks.register<Test>("sqlPlacementGate") {
        group = "verification"
        description = "verifySqlPlacement reports zero findings in each of the five modules (INV-Q-7422)."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        include("**/SqlPlacementResultGate*")
        listOf(
            "ingestion-domain",
            "ingestion-application",
            "ingestion-adapters",
            "ingestion-bootstrap",
            "ingestion-contract",
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
