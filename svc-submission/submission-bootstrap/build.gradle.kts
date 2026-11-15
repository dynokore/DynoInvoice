// submission-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":submission-adapters"))
    implementation(project(":submission-application"))
    implementation(project(":submission-contract"))
    implementation(project(":submission-domain"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.tenancyJooq.impl)
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
    testImplementation(libs.platform.serviceRuntime.api) {
        capabilities { requireCapability("com.dynokore.platform:service-runtime-test-fixtures") }
    }
    testImplementation(libs.springBoot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(testFixtures(project(":submission-adapters")))
}

// UnsubmittedCountMeasurementJobTest validates the written payload against the schema of the pinned contract bundle that
// :submission-contract stages; the build fails when the schema is missing from the bundle (INV-AC-23028).
val contractSchemas = layout.buildDirectory.dir("contract-schemas")
val stageContractSchemas =
    tasks.register<Copy>("stageContractSchemas") {
        val schema =
            project(":submission-contract").layout.buildDirectory.file(
                "contracts-repo/.bundle/submission/schemas/unsubmitted-document-count-measured-payload.schema.json",
            )
        from(schema)
        into(contractSchemas.map { it.dir("gst/contracts/submission/schemas") })
        doFirst {
            check(
                schema.get().asFile.isFile,
            ) { "the pinned platform-contracts bundle has no ${schema.get().asFile.name}" }
        }
    }
sourceSets.test { resources.srcDir(contractSchemas) }
tasks.named("processTestResources") { dependsOn(stageContractSchemas) }
