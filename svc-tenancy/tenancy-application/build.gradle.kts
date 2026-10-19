// tenancy-application — use cases and ports (TPL-01, ARC-03): framework-free.
plugins { id("gst.application") }

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(project(":tenancy-domain"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.serviceRuntime.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}
