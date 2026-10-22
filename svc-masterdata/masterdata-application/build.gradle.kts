// masterdata-application — TPL-01, ARC-03 (INV-T-4942): use cases and ports, framework-free.
plugins {
    id("gst.application")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(libs.platform.partyRegistry.api)
    implementation(project(":masterdata-domain"))
    implementation(libs.platform.tenancyKernel.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
