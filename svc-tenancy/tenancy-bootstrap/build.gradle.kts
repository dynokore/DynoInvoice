// tenancy-bootstrap — the Spring Boot application and its composition root (TPL-01, TPL-16, TPL-18).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":tenancy-adapters"))
    implementation(project(":tenancy-contract"))
    implementation(project(":tenancy-application"))
    implementation(project(":tenancy-domain"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.tenancyJooq.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.auditLog.impl)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.jooq)
    implementation(libs.jackson.databind)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.tenancyJooq.impl)
    implementation(libs.platform.configResolver.api)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.gstCatalogue)
    testImplementation(libs.springBoot.starterTest)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
