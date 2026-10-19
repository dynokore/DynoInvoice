// acceptance (TPL-01): test-only; scenario tests traced to Keel criteria by key.
plugins {
    id("gst.kotlin-base")
}

dependencies {
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
