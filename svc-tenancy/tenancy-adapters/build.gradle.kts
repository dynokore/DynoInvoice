// tenancy-adapters — persistence (jOOQ only, CS-18), HTTP, events (TPL-01, ARC-03).
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
}

// STK-15: the records are generated from the Flyway migrations (src/main/resources/db/migration) applied to PostgreSQL.
gstJooqCodegen {
    schema = "tenancy"
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":tenancy-domain"))
    implementation(project(":tenancy-application"))
    implementation(project(":tenancy-contract"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.jooq)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)
}
