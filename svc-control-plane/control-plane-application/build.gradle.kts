// control-plane-application: use cases and ports, framework-free (ARC-03).
plugins {
    id("gst.application")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(project(":control-plane-domain"))
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.crypto.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
}

tasks.withType<Test> { useJUnitPlatform() }
