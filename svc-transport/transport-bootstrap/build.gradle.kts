// transport-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":transport-adapters"))
    implementation(project(":transport-contract"))
    implementation(project(":transport-application"))
    implementation(project(":transport-domain"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.auditLog.impl)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterJooq)
    implementation(libs.jooq)
    implementation(libs.micrometer.core)
    // the /metrics endpoint of the management server (API-33): the Prometheus scrape format
    runtimeOnly(libs.micrometer.registryPrometheus)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.productRuntime.api)
    testImplementation(libs.springBoot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(testFixtures(project(":transport-adapters")))
}
