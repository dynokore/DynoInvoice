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
    // TenancyProbe, tenancy-jooq's test fixture: the only raw-SQL place in tests (CS-18); the published
    // test-fixtures variant
    testImplementation(libs.platform.tenancyJooq.api) {
        capabilities { requireCapability("com.dynokore.platform:tenancy-jooq-test-fixtures") }
    }
    testImplementation(libs.platform.tenancyKernel.api) {
        // SignedBinding, which TenancyProbe signs with
        capabilities { requireCapability("com.dynokore.platform:tenancy-kernel-test-fixtures") }
    }
    testImplementation(libs.assertj.core) // AssertableApplicationContext extends AssertJ's AssertProvider
    testImplementation(libs.jooq)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.snakeyaml) // the gates read the rules contract documents
    // ArchitectureRules, RawSqlPlacement, SqlGuards, MigrationLint (STK-41)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.platform.outboxAndQueue.api)
    testImplementation(libs.platform.tenancyKernel.api)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    // ArchitectureTest scans the generated contract layer of rules-contract
    dependsOn(":rules-contract:generateContractServer")
    useJUnitPlatform()
    filter { excludeTestsMatching("*Gate") } // the two repository gates have their own tasks below
    jvmArgs("-Duser.timezone=UTC") // CS-41
}

// ---- the two repository gates (INV-T-4965), each its own `check` task that fails closed ----
// acKeyCoverage: ac-keys.tsv against the test names of every module. verifyOperations: operations.tsv against the
// staged
// rules contract (rules-contract/build/generated/contract-resources) and the controllers of rules-adapters. Their logic
// is Kotlin in this module's test sources (gates/), probed by AcKeyCoverageTest and OperationsTableTest.
fun gateTask(
    name: String,
    gateClass: String,
    what: String,
) = tasks.register<Test>(name) {
    group = "verification"
    description = what
    val testSet = sourceSets.test.get()
    testClassesDirs = testSet.output.classesDirs
    classpath = testSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(gateClass)
        isFailOnNoMatchingTests = true
    }
    systemProperty("gst.repo.root", rootDir.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL // the finding names the row
    }
    // the inputs are files of the repository, not Gradle inputs of this module: always run
    outputs.upToDateWhen { false }
}

val acKeyCoverage =
    gateTask(
        "acKeyCoverage",
        "*AcKeyCoverageGate",
        "Fails when ac-keys.tsv is missing, empty or not covered by test names.",
    )
val verifyOperations =
    gateTask(
        "verifyOperations",
        "*OperationsGate",
        "Fails when operations.tsv and the rules contract disagree or a done task still answers 501.",
    )
verifyOperations.configure { dependsOn(":rules-contract:stageContractResources") }

tasks.named("check") { dependsOn(acKeyCoverage, verifyOperations) }

// The shared unit-test job runs only `test`: finalizing it with the gates runs them there too, and a gate failure fails
// the job (verify-rules disables the Test tasks, so the gates stay out of that job).
tasks.named("test") { finalizedBy(acKeyCoverage, verifyOperations) }
