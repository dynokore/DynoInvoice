// rules-adapters/build.gradle.kts — TPL-01, ARC-06/07, STK-15 (INV-T-4965).
plugins {
    id("gst.adapters") // Spring, jOOQ, Resilience4j allowed here
    id("gst.jooq-codegen") // STK-15: jOOQ code generated from the Flyway migrations (INV-T-4223)
}

// The jOOQ code is generated from the production migrations applied to PostgreSQL (STK-15). The policies name the
// signed-binding functions that tenancy-kernel's BindingKeyMigration creates as the sys login in an installation, which
// the code-generation database cannot do (initScripts may only create roles, INV-Q-4457); a codegen-only stub migration
// V0 therefore travels with them into the throwaway database. It is not a service migration.
val prepareCodegenMigrations =
    tasks.register<Sync>("prepareCodegenMigrations") {
        from("src/main/resources/db/migration")
        from("src/codegen") { include("V0__*.sql") }
        into(layout.buildDirectory.dir("codegen-migrations"))
    }

gstJooqCodegen {
    schema = "rules"
    migrations.set(layout.buildDirectory.dir("codegen-migrations"))
    initScripts.from("src/codegen/roles.sql")
}

tasks.named("generateJooq") { dependsOn(prepareCodegenMigrations) }

dependencies {
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
