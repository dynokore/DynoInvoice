// canonical-domain (TPL-01, ARC-03): pure Kotlin, no framework type. java.sql and javax.sql types may be named
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

// NettingLinksTest compares DocumentFunction with the ingestion contract's enum; canonical-contract stages the pinned
// bundle while it is configured.
evaluationDependsOn(":canonical-contract")

tasks.withType<Test>().configureEach {
    systemProperty(
        "gst.ingestion.schema",
        project(":canonical-contract")
            .layout.buildDirectory
            .file("contracts-repo/.bundle/ingestion/schemas/transaction-request.schema.json")
            .get()
            .asFile.path,
    )
}
