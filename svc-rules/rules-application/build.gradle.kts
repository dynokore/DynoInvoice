// rules-application/build.gradle.kts — TPL-01, ARC-03 (INV-T-4965).
plugins { id("gst.application") }

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(project(":rules-domain"))
    implementation(libs.platform.rulepackKernel.api)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.serviceRuntime.api) // authorisation and replicas only (ARC §2.1)
    implementation(libs.gst.gstCatalogue)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.platform.rulepackKernel.impl)
    testImplementation(libs.kotest.assertionsCore)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41
}
