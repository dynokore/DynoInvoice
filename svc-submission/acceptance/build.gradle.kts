// acceptance (TPL-01): test-only; scenario tests traced to Keel criteria by key.
plugins {
    id("gst.kotlin-base")
}

dependencies {
    testImplementation(platform(libs.springBoot.dependencies))
    testImplementation(project(":submission-contract"))
    testImplementation(project(":submission-adapters"))
    testImplementation(libs.springBoot.starterWeb)
    testImplementation(libs.snakeyaml)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
}

// ArchitectureTest reads the generated contract sources.
tasks.withType<Test>().configureEach {
    dependsOn(":submission-contract:generateContractServer")
}

// OperationsGateTest reads the pinned contract documents staged by submission-contract and the table at the root.
tasks.withType<Test>().configureEach {
    val contractDir = project(":submission-contract").layout.buildDirectory.dir(
        "contracts-repo/platform-contracts/submission/${libs.versions.platformContractsBundle.get()}",
    )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
    systemProperty("gst.root.dir", rootProject.projectDir.path)
}
