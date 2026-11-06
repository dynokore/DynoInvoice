// console-bff-adapters (TPL-01, TPL-11, TPL-12): persistence and db/migration/. No controller yet (later tasks).
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
    `java-test-fixtures`
}

// The jars that hold BindingKeyMigration: gst.jooq-codegen installs the signed-binding functions from them before
// the first migration runs (INV-Q-7418 A, platform-build INV-T-7430). Build time only.
val bindingKernel: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

gstJooqCodegen {
    schema = "console"
    initScripts.from("src/testFixtures/resources/db/roles.sql")
    bindingSource.from(bindingKernel)
    bindingSysRole = "console_sys"
    bindingRuntimeRole = "console_runtime"
}

dependencies {
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":console-bff-application"))
    implementation(project(":console-bff-domain"))
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.serviceRuntime.impl)
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
    testImplementation(libs.platform.tenancyKernel.impl)
}
