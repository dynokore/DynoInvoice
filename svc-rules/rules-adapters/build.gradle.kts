// rules-adapters/build.gradle.kts — TPL-01, ARC-06/07, STK-15 (INV-T-4965).
plugins {
    id("gst.adapters") // Spring, jOOQ, Resilience4j allowed here
    id("gst.jooq-codegen") // STK-15: jOOQ code generated from the Flyway migrations (INV-T-4223)
}

// The jars that hold tenancy-kernel's BindingKeyMigration: the codegen installs the signed-binding functions of the schema
// in its throwaway container before the migrations (INV-Q-7418), as an installation does as the sys login.
val bindingKernel: Configuration by configurations.creating {
    isCanBeConsumed = false
}

// The jOOQ code is generated from the production migrations applied to PostgreSQL (STK-15).
gstJooqCodegen {
    schema = "rules"
    initScripts.from("src/codegen/roles.sql")
    bindingSource.from(bindingKernel)
    bindingSysRole = "rules_sys"
    bindingRuntimeRole = "rules_runtime"
}

dependencies {
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":rules-domain"))
    implementation(project(":rules-application"))
    implementation(project(":rules-contract"))
    implementation(libs.platform.rulepackKernel.api)
    implementation(libs.platform.rulepackKernel.impl)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.jooq)
    implementation(libs.spring.context)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jackson.databind)
    implementation(libs.gst.gstCatalogue)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.jsonSchemaValidator) // the AsyncAPI payload schemas of every published channel
    testImplementation(libs.snakeyaml) // asyncapi.yaml of the pinned contract
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    // -Dgst.regenerate=true rewrites the committed generated migration (MigrationDdlTest)
    System.getProperty("gst.regenerate")?.let { systemProperty("gst.regenerate", it) }
    jvmArgs("-Duser.timezone=UTC") // CS-41
}
