// access-adapters — TPL-01 (INV-T-4934): HTTP, events, persistence. Spring and jOOQ are allowed here; all database
// access is jOOQ only under the INV-T-4201 rules.
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
}

gstJooqCodegen {
    schema = "access"
    // The roles, the schema and the signed-binding functions the migrations grant to and the policies call (TPL-11).
    initScripts.from("src/test/resources/db/provisioning.sql")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":access-application"))
    implementation(project(":access-domain"))
    implementation(project(":access-contract"))
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
