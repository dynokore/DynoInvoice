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

// ScopeAgreementTest reads canonical/openapi.yaml of the same bundle. CreditNoteOrderingTest reads lifecycle-states.schema.json from the contract bundle that submission-contract stages.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":submission-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/submission/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
    systemProperty(
        "gst.canonical.openapi",
        project(":submission-contract")
            .layout.buildDirectory
            .file("contracts-repo/canonical/openapi.yaml")
            .get()
            .asFile.path,
    )
    systemProperty(
        "gst.rules.handlers.schema",
        project(":submission-contract")
            .layout.buildDirectory
            .file("contracts-repo/rules/schemas/scenario-pack-handlers.schema.json")
            .get()
            .asFile.path,
    )
}
