// rules-domain/build.gradle.kts — TPL-01, ARC-03 (INV-T-4965).
plugins { id("gst.domain") }

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(libs.platform.rulepackKernel.api)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.gst.gstCatalogue) // L3: ErrorCode (RUL-002, RUL-007), generated from invoice-docs/model (CS-13)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.platform.rulepackKernel.impl) // InMemoryPackRegistry, the reference registry
    testImplementation(libs.kotest.assertionsCore)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41
}
