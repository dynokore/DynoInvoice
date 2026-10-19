// rules-bootstrap/build.gradle.kts — TPL-01, TPL-18 (INV-T-4965).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

springBoot { mainClass.set("com.dynokore.gst.rules.bootstrap.RulesApplicationKt") }

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":rules-adapters"))
    implementation(project(":rules-application"))
    implementation(project(":rules-domain"))
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.rulepackKernel.api)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.jooq)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    // The impl halves service-runtime's composition root wires when they are on the classpath (TPL-01, TPL-18).
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.platform.auditLog.impl)
    implementation(libs.platform.rulepackKernel.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    runtimeOnly(libs.postgresql)
    // The image also runs service-runtime's MigrationRunner, the migration Job's entry point (TPL-12, ARC-20).
    runtimeOnly(libs.springBoot.flyway)
    runtimeOnly(libs.springBoot.jooq)
    runtimeOnly(libs.flyway.core)
    runtimeOnly(libs.flyway.databasePostgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.springBoot.test)
    testImplementation(libs.testcontainers.postgresql) // real PostgreSQL only (STK-14, STK-15); H2 is banned
    // TenancyProbe, tenancy-jooq's test fixture: the only raw-SQL place in tests (CS-18); the published test-fixtures variant
    testImplementation(libs.platform.tenancyJooq.api) {
        capabilities { requireCapability("com.dynokore.platform:tenancy-jooq-test-fixtures") }
    }
    testImplementation(libs.platform.tenancyKernel.api) {
        capabilities { requireCapability("com.dynokore.platform:tenancy-kernel-test-fixtures") } // SignedBinding, which TenancyProbe signs with
    }
    testImplementation(libs.assertj.core) // AssertableApplicationContext extends AssertJ's AssertProvider
    testImplementation(libs.jooq)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules) // ArchitectureRules, RawSqlPlacement, SqlGuards, MigrationLint (STK-41)
    testImplementation(libs.platform.outboxAndQueue.api)
    testImplementation(libs.platform.tenancyKernel.api)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    // ArchitectureTest scans the generated contract layer of rules-contract
    dependsOn(":rules-contract:generateContractServer")
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41
}
