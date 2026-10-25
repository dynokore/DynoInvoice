// submission-domain (TPL-01, ARC-03): pure Kotlin, no framework type. java.sql and javax.sql types may be named
// (owner answer INV-Q-5331 (D)); Spring and jOOQ may not.
plugins {
    id("gst.domain")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
}

// CreditNoteOrderingTest reads lifecycle-states.schema.json from the contract bundle that submission-contract stages.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":submission-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/submission/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
}
