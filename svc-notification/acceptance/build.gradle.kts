// acceptance (TPL-01): test-only; scenario tests traced to Keel criteria by key.
plugins {
    id("gst.kotlin-base")
}

dependencies {
    testImplementation(platform(libs.springBoot.dependencies))
    testImplementation(project(":notification-contract"))
    testImplementation(project(":notification-adapters"))
    testImplementation(libs.springBoot.starterWeb)
    testImplementation(libs.snakeyaml)
    testImplementation(project(":notification-bootstrap"))
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
    dependsOn(":notification-contract:generateContractServer")
}

// OperationsGateTest reads the pinned contract documents staged by notification-contract and the table at the root.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":notification-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/notification/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
    systemProperty("gst.root.dir", rootProject.projectDir.path)
}

// acKeyCoverage (INV-T-5972): fails closed over the JUnit XML of every module's test tasks; see AcKeyCoverage.kt.
tasks.named<Test>("test") {
    exclude("**/AcKeyCoverageGate*")
}

val acKeyCoverage =
    tasks.register<Test>("acKeyCoverage") {
        group = "verification"
        description =
            "Every wave 4 row of ac-keys.tsv has an executed test named by its key, and no test names a key outside the table."
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
