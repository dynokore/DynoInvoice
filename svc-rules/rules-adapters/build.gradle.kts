// rules-adapters/build.gradle.kts — TPL-01, ARC-06/07, STK-15 (INV-T-4965).
plugins {
    id("gst.adapters") // Spring, jOOQ, Resilience4j allowed here
    id("gst.jooq-codegen") // STK-15: jOOQ code generated from the Flyway migrations (INV-T-4223)
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":rules-domain"))
    implementation(project(":rules-application"))
    implementation(project(":rules-contract"))
    implementation(libs.platform.rulepackKernel.api)
    implementation(libs.platform.rulepackKernel.impl)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.jooq)
    implementation(libs.spring.context)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jackson.databind)
    implementation(libs.gst.gstCatalogue)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertionsCore)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41
}
