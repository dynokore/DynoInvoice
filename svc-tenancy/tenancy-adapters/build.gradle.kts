// tenancy-adapters — persistence (jOOQ only, CS-18), HTTP, events (TPL-01, ARC-03).
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
}

// The jars that hold tenancy-kernel's BindingKeyMigration: the codegen installs the signed-binding functions of the schema
// in its throwaway container before the migrations (INV-Q-7418), as the fixture does in an environment.
val bindingKernel: Configuration by configurations.creating {
    isCanBeConsumed = false
}

// STK-15: the records are generated from the Flyway migrations (src/main/resources/db/migration) applied to PostgreSQL.
gstJooqCodegen {
    initScripts.from("src/integrationTest/resources/roles.sql")
    schema = "tenancy"
    bindingSource.from(bindingKernel)
    bindingSysRole = "tenancy_sys"
    bindingRuntimeRole = "tenancy_runtime"
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
    implementation(libs.platform.productRuntime.api)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.snakeyaml)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.jooq)
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.platform.serviceRuntime.impl)
    testImplementation(libs.platform.productRuntime.impl)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)
}
