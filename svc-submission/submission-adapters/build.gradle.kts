// submission-adapters (TPL-01, TPL-11, TPL-12): http, events, persistence and db/migration/.
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
    `java-test-fixtures`
}

gstJooqCodegen {
    schema = "submission"
    initScripts.from("src/testFixtures/resources/db/roles.sql")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":submission-application"))
    implementation(project(":submission-domain"))
    implementation(project(":submission-contract"))
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.auditLog.impl)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterValidation)
    implementation(libs.swagger.annotations)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.databasePostgresql)
    testFixturesImplementation(platform(libs.platform.bom))
    testFixturesImplementation(libs.platform.tenancyKernel.api)
    testFixturesImplementation(libs.platform.tenancyKernel.impl)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.databasePostgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
