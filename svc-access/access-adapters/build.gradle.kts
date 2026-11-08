// access-adapters — TPL-01 (INV-T-4934): HTTP, events, persistence. Spring and jOOQ are allowed here; all database
// access is jOOQ only under the INV-T-4201 rules.
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
    schema = "access"
    // The roles the migrations grant to (TPL-11).
    initScripts.from("src/test/resources/db/provisioning.sql")
    bindingSource.from(bindingKernel)
    bindingSysRole = "access_sys"
    bindingRuntimeRole = "access_runtime"
}

dependencies {
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)
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
    implementation(libs.platform.configResolver.api)
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
    testImplementation(libs.platform.configResolver.impl)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

// The event-log topics of the pinned platform-contracts bundle, which access-contract already unpacks: the allowed
// producers of SignedLinkRequested are read from this one file at start-up (INV-Q-17900), never kept as a constant.
val topicsFile =
    rootProject.layout.projectDirectory.file(
        "access-contract/build/contracts-repo/.bundle/event-log/topics.yaml",
    )
val stageEventLogTopics by tasks.registering(Copy::class) {
    from(topicsFile) { into("gst/contracts/event-log") }
    into(layout.buildDirectory.dir("generated/contracts"))
    doFirst { check(topicsFile.asFile.isFile) { "${topicsFile.asFile} is missing: the bundle is not staged" } }
}
tasks.processResources { from(stageEventLogTopics) }

// The payload schema of AuthorityChanged is owned by service-runtime in the bundle (INV-T-9355): the payload tests read the
// file the pinned bundle holds and never a copy, so a bundle step reaches them. Test resources only.
val authoritySchemaFile =
    rootProject.layout.projectDirectory.file(
        "access-contract/build/contracts-repo/.bundle/service-runtime/schemas/authority-changed-payload.schema.json",
    )
val stageAuthoritySchema by tasks.registering(Copy::class) {
    from(authoritySchemaFile) { into("gst/contracts/service-runtime/schemas") }
    into(layout.buildDirectory.dir("generated/contract-test-resources"))
    doFirst {
        check(
            authoritySchemaFile.asFile.isFile,
        ) { "${authoritySchemaFile.asFile} is missing: the bundle is not staged" }
    }
}
tasks.processTestResources { from(stageAuthoritySchema) }

tasks.withType<Test>().configureEach { useJUnitPlatform() }
