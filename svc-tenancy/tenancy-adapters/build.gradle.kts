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

// The pinned platform-contracts bundle carries the rules contract: the calendar pack decoder validates a release's
// contents against its calendar-pack-contents schema, staged here as a classpath resource (INV-T-5055).
val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

val stageRulesSchemas =
    tasks.register<Copy>("stageRulesSchemas") {
        from(zipTree(contractsBundle.singleFile)) {
            include("rules/schemas/calendar-pack-contents.schema.json")
            eachFile { path = name }
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("generated/rules-schemas/gst/contracts/rules/schemas"))
    }

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/rules-schemas").map { it.asFile }.get())
}

tasks.named("processResources") { dependsOn(stageRulesSchemas) }

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":tenancy-domain"))
    implementation(project(":tenancy-application"))
    implementation(project(":tenancy-contract"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.calendarAndPeriods.api)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.configResolver.api)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.snakeyaml)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.jsonSchemaValidator)
    implementation(libs.jooq)
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.platform.calendarAndPeriods.impl)
    testImplementation(libs.platform.configResolver.impl)
    testImplementation(libs.platform.serviceRuntime.impl)
    testImplementation(libs.platform.productRuntime.impl)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)
}
