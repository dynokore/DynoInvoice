// canonical-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

// The pinned platform-contracts bundle, resolved on its own (non-transitive, the coordinates of canonical-contract).
val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":canonical-adapters"))
    implementation(project(":canonical-application"))
    implementation(project(":canonical-domain"))
    implementation(project(":canonical-contract"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterJooq)
    implementation(libs.jooq)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(testFixtures(project(":canonical-adapters")))
    testImplementation(project(":canonical-application"))
    testImplementation(project(":canonical-domain"))
    testImplementation(libs.platform.serviceRuntime.api) {
        capabilities { requireCapability("com.dynokore.platform:service-runtime-test-fixtures") }
    }
}

// The tests of the published CanonicalVersionCreated schema read the staged contract documents (INV-T-22849).
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":canonical-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/canonical/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
}

// The page limit of the dry-run sample is read from the one contract file at run time: exactly this entry of the
// pinned bundle is staged into the classpath resources (contract-first, no committed copy, no literal number).
val sampleSchemaEntry = "service-runtime/schemas/pack-dry-run-sample-recorded-payload.schema.json"
val reprocessingSchemaEntry = "service-runtime/schemas/pack-reprocessing-population-recorded-payload.schema.json"
val stageSampleSchema by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/sample-schema/resources"))
    into("gst/contracts") {
        from(zipTree(contractsBundle.singleFile)) { include(sampleSchemaEntry, reprocessingSchemaEntry) }
    }
    includeEmptyDirs = false
}
sourceSets.main { resources.srcDir(stageSampleSchema.map { it.destinationDir }) }
tasks.processResources { dependsOn(stageSampleSchema) }
tasks.withType<Test>().configureEach {
    dependsOn(tasks.bootJar)
    systemProperty(
        "gst.boot.jar",
        tasks.bootJar
            .get()
            .archiveFile
            .get()
            .asFile.path,
    )
}
