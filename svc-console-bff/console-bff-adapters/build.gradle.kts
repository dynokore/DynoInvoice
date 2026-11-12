// console-bff-adapters (TPL-01, TPL-11, TPL-12): persistence (db/migration/), the session filter and the replica adapter.
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
    `java-test-fixtures`
}

gstJooqCodegen {
    schema = "console_bff"
    initScripts.from("src/testFixtures/resources/db/roles.sql")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":console-bff-application"))
    implementation(project(":console-bff-domain"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.databasePostgresql)
    testFixturesImplementation(platform(libs.platform.bom))
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.databasePostgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.platform.serviceRuntime.api)
}
