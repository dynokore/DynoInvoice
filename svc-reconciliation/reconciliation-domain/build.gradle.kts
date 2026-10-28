// reconciliation-domain (TPL-01, ARC-03): pure Kotlin, no framework type. java.sql and javax.sql types may be named
// (owner answer INV-Q-5331 (D)); Spring and jOOQ may not.
plugins {
    id("gst.domain")
}

dependencies {
    api(platform(libs.platform.bom))
    api(libs.platform.reconciliationEngine.api)
    implementation(libs.platform.reconciliationEngine.impl)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
}
