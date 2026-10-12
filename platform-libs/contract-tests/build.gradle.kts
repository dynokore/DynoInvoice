// contract-tests/build.gradle.kts — the consumer-driven contract suite of every Wave 1 module (INV-T-2877, FR-AH.14,
// INV-REQ-1225; coordinator ruling INV-Q-3925). Test only: no main code, no publication, not in platform-libs-bom.
//
// It holds, per boundary:
//   - Pact JVM consumer tests (STK-40) for every wire edge whose consumer code is in platform-libs: HTTP pacts of
//     product-runtime's tenancy-svc and access-svc fallback clients, message pacts of OrganisationStructureChanged,
//     EntityProfileChanged, AuthorityChanged (product-runtime's feeds) and PolicyChanged (service-runtime's
//     PlatformEventReader). Each message's content validates against its pinned platform-contracts schema (CS-38).
//   - config-resolver's provider verification of the PolicyChanged pact against the event its real policy writer
//     emits (INV-REQ-1225 b2): from the local pact on every run, and from the broker with the result published when
//     a broker is configured (`pactBrokerVerify`, run by the CI `contract-tests` job after `pactPublish`).
//   - The module-API consumer contract tests of the L1 modules, run here from each module's own compiled tests
//     (`moduleApiContracts`), and those of the boundaries that had none, in this module's own tests.
// The CI `contract-tests` job (.gitlab-ci.yml) runs `check` here on every merge-request and main pipeline.
plugins {
    id("gst.kotlin-base")
    id("gst.pact")
}

// Every module whose classes the suite runs, evaluated first so their source sets and impl jars can be read.
val suiteModules =
    listOf(
        "service-runtime", "outbox-and-queue", "rulepack-kernel", "tenancy-kernel", "evidence-store",
        "adapter-runtime", "config-resolver", "audit-log", "product-runtime",
    )
suiteModules.forEach { evaluationDependsOn(":$it") }

fun sourceSet(
    module: String,
    name: String,
) = project(":$module").the<SourceSetContainer>()[name]

fun implJar(module: String) = files(project(":$module").tasks.named("implJar"))

val generatedContracts =
    listOf(
        ":product-runtime-tenancy-client",
        ":product-runtime-access-client",
        ":product-runtime-structure-events",
        ":product-runtime-profile-events",
        ":product-runtime-authority-events",
        ":product-runtime-policy-events",
    )

dependencies {
    // The consumer code under test, exactly as an embedding service gets it: api and impl halves.
    testImplementation(project(":product-runtime"))
    testImplementation(sourceSet("product-runtime", "impl").output)
    generatedContracts.forEach { testImplementation(project(it)) }
    testImplementation(project(":service-runtime"))
    testImplementation(sourceSet("service-runtime", "impl").output)
    testImplementation(project(":config-resolver"))
    testImplementation(implJar("config-resolver"))
    testImplementation(implJar("tenancy-kernel"))
    testImplementation(implJar("outbox-and-queue"))
    testImplementation(libs.gstCatalogue)
    // config-resolver's write harness (a real JdbcPolicyWriter on PostgreSQL) for the PolicyChanged provider side,
    // and service-runtime's contract validator (JSON Schema 2020-12 over the pinned platform-contracts files).
    testImplementation(sourceSet("config-resolver", "test").output)
    testImplementation(sourceSet("service-runtime", "implTest").output)
    testImplementation(platform(libs.springBoot.dependencies))
    testImplementation(libs.springWeb)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.moduleKotlin)
    testImplementation(libs.snakeyaml) // ContractValidator reads the OpenAPI documents
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.pact.consumerJunit5)
    testImplementation(libs.pact.providerJunit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testImplementation(libs.kotest.assertionsCore)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)
    // Spring Boot's BOM manages Testcontainers 2.x; config-resolver's write harness is built on the repository's
    // 1.21 line (libs.versions.toml), so the core artifact is held to it, as service-runtime's implTest does.
    constraints {
        testImplementation(libs.testcontainers.core) {
            version { strictly(libs.versions.testcontainers.get()) }
        }
    }
}

// The pinned platform-contracts files the message contents are validated against: product-runtime stages them from
// the published bundle at the pinned version (stageContracts); the suite reads tenancy's, access's and
// service-runtime's as classpath resources under contracts/, the layout ContractValidator reads.
val bundleVersion = libs.versions.platformContractsBundle
val stagedContracts =
    tasks.register<Sync>("stageContractSchemas") {
        from(project(":product-runtime").layout.buildDirectory.dir("contracts-repo/.bundle")) {
            include("tenancy/**", "access/**", "service-runtime/**")
        }
        into(layout.buildDirectory.dir("generated/contracts/contracts"))
    }
sourceSets["test"].resources.srcDir(stagedContracts.map { it.destinationDir.parentFile })

fun Test.suiteDefaults() {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
    systemProperty("pact.rootDir", layout.buildDirectory.dir("pacts").get().asFile.path)
    systemProperty("contracttests.bundleVersion", bundleVersion.get())
    systemProperty("contracttests.gstCatalogueVersion", libs.versions.gstCatalogue.get())
}

// Consumer pacts first: they write build/pacts, which the provider verification reads.
tasks.test {
    suiteDefaults()
    filter { excludeTestsMatching("com.dynokore.platform.contracttests.provider.*") }
}

val providerVerification =
    tasks.register<Test>("providerVerification") {
        group = "verification"
        description = "INV-REQ-1225 b2: config-resolver verifies the PolicyChanged pact against its real policy writer."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        suiteDefaults()
        dependsOn(tasks.test)
        filter {
            includeTestsMatching("com.dynokore.platform.contracttests.provider.*")
            excludeTestsMatching("*BrokerTest")
        }
    }

// Run by CI after pactPublish: verifies the pacts the broker holds for config-resolver and publishes the result.
tasks.register<Test>("pactBrokerVerify") {
    group = "verification"
    description = "INV-REQ-1225 b2: verifies config-resolver's pacts from the Pact Broker and publishes the result."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    suiteDefaults()
    outputs.upToDateWhen { false }
    filter { includeTestsMatching("com.dynokore.platform.contracttests.provider.*BrokerTest") }
    // The broker URL and basic-auth pair are read by the test from the masked CI variables PACT_BROKER_BASE_URL,
    // PACT_BROKER_USERNAME and PACT_BROKER_PASSWORD (Pact's expression resolver falls back to the environment), so
    // no credential becomes a task input (P99).
    fun ci(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    systemProperty("pact.verifier.publishResults", "true")
    systemProperty("pact.provider.version", ci("CI_COMMIT_SHA") ?: "0.0.0-local")
    ci("CI_COMMIT_REF_NAME")?.let { systemProperty("pact.provider.branch", it) }
    // The pacts of this commit's branch (just published by pactPublish) and of the main branch.
    val branch = ci("CI_COMMIT_REF_NAME")
    val selectors = listOfNotNull("""{"mainBranch":true}""", branch?.let { """{"branch":"$it"}""" })
    systemProperty("pactbroker.consumerversionselectors.rawjson", selectors.joinToString(",", "[", "]"))
}

// The module-API consumer contract tests the L1 modules hold in their own test source sets (the
// ModuleApiConsumerContractTest pattern: a consumer written the way a service writes it, against the real
// implementation), run here as part of the suite from each module's compiled tests and classpath.
val moduleApiContractTests =
    mapOf(
        "outbox-and-queue" to "test",
        "rulepack-kernel" to "test",
        "tenancy-kernel" to "test",
        "evidence-store" to "test",
        "audit-log" to "test",
    )
val moduleApiContracts =
    tasks.register("moduleApiContracts") {
        group = "verification"
        description = "Runs every L1 module's own module-API consumer contract test as part of the contract suite."
    }
moduleApiContractTests.forEach { (module, set) ->
    val task =
        tasks.register<Test>("moduleApiContract-$module") {
            group = "verification"
            description = "$module's module-API consumer contract test (the ModuleApiConsumerContractTest pattern)."
            val source = sourceSet(module, set)
            testClassesDirs = source.output.classesDirs
            classpath = source.runtimeClasspath
            suiteDefaults()
            filter {
                includeTestsMatching("*ModuleApiConsumerContractTest")
                isFailOnNoMatchingTests = true
            }
            reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/moduleApiContract-$module"))
        }
    moduleApiContracts.configure { dependsOn(task) }
}

tasks.named("check") {
    dependsOn(providerVerification, moduleApiContracts)
}
