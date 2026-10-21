// payload-application (TPL-01, ARC-03): use cases and ports; framework-free.
plugins {
    id("gst.application")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(project(":payload-domain"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.configResolver.api)
    implementation(libs.platform.serviceRuntime.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
}
