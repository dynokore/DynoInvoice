// masterdata-adapters — TPL-01 (INV-T-4942): HTTP, events, persistence. Spring and jOOQ are allowed here; all database
// masterdata is jOOQ only under the INV-T-4201 rules.
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
}

// The jars that hold tenancy-kernel's BindingKeyMigration: the codegen installs the signed-binding functions of the schema
// in its throwaway container before the migrations (INV-Q-7418), as the fixture does in an environment.
val bindingKernel: Configuration by configurations.creating {
    isCanBeConsumed = false
}

gstJooqCodegen {
    schema = "masterdata"
    // The roles the migrations grant to (TPL-11).
    initScripts.from("src/test/resources/db/provisioning.sql")
    bindingSource.from(bindingKernel)
    bindingSysRole = "masterdata_sys"
    bindingRuntimeRole = "masterdata_runtime"
}

dependencies {
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)
    implementation(platform(libs.platform.bom))
    implementation(libs.platform.partyRegistry.api)
    implementation(libs.gstCatalogue)
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":masterdata-application"))
    implementation(project(":masterdata-domain"))
    implementation(project(":masterdata-contract"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.auditLog.impl)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jooq)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.snakeyaml)
    implementation(libs.jakartaServlet)
    testImplementation(libs.springBoot.starterTest)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.platform.productRuntime.impl)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
