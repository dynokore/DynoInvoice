// control-plane-bootstrap: the Spring Boot application and its composition (TPL-18). All wiring lives here.
// The Spring Boot plugin brings tools.jackson.core 3.1.5 (HIGH CVEs, fixed in 3.1.7) onto this module's plugin classpath;
// the constraint lifts both to 3.1.7, as platform-build does for its own classpath. Remove once the plugin resolves 3.1.7+.
buildscript {
    dependencies {
        constraints {
            classpath("tools.jackson.core:jackson-core:3.1.7")
            classpath("tools.jackson.core:jackson-databind:3.1.7")
        }
    }
}

plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":control-plane-adapters"))
    implementation(project(":control-plane-application"))
    implementation(project(":control-plane-domain"))
    implementation(project(":control-plane-contract"))
    implementation(libs.platform.serviceRuntime)
    implementation(libs.platform.productRuntime)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterFlyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.databasePostgresql)
    implementation(libs.jooq)
    implementation(libs.platform.crypto.api)
    // EntityRef and TenantId, the parameter types of JobCommandAuthorizer
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.rulepackKernel.api)
    // the BouncyCastleCms bean of service-runtime's CryptoWiring (DetachedSigner)
    runtimeOnly(libs.platform.crypto)
    // the OutboxWriter and the relay of service-runtime's OutboxAndQueueWiring, and the tables the job runtime writes its events to
    runtimeOnly(libs.platform.outboxAndQueue)
    // the OutboxAuditWriter bean of service-runtime's AuditLogWiring (the service has an OutboxWriter, so it binds the outbox audit writer)
    runtimeOnly(libs.platform.auditLog)
    // the platform-scoped tables of config-resolver's ConfigSchema, read by service-runtime's TenancyJooqWiring
    runtimeOnly(libs.platform.configResolver)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
    // AssertableApplicationContext, the result type of ApplicationContextRunner.run, names AssertJ in its supertypes
    testImplementation(libs.assertj.core)
}

tasks.withType<Test> { useJUnitPlatform() }
