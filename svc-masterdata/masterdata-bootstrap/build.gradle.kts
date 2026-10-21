// masterdata-bootstrap — TPL-01, TPL-18 (INV-T-4942): the composition root and the service's configuration.
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":masterdata-adapters"))
    implementation(project(":masterdata-contract"))
    implementation(project(":masterdata-application"))
    implementation(project(":masterdata-domain"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.jooq)
    implementation(libs.jackson.databind)
    implementation(libs.jakartaServlet)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.tenancyJooq.impl)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.springBoot.starterTest)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.platform.productRuntime.impl)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
