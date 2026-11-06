// console-bff-adapters (TPL-01, TPL-11): no database, no controller yet (later tasks; INV-Q-17448 C).
plugins {
    id("gst.adapters")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":console-bff-application"))
    implementation(project(":console-bff-domain"))
    implementation(libs.platform.serviceRuntime.impl)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
}
