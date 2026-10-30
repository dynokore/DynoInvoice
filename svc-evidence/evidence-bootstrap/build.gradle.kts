// evidence-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":evidence-adapters"))
    implementation(project(":evidence-application"))
    implementation(project(":evidence-domain"))
    implementation(libs.platform.evidenceStore.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(project(":evidence-contract"))
    implementation(libs.platform.serviceRuntime.impl)
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
    testImplementation(libs.springBoot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.platform.evidenceStore.impl)
    testImplementation(testFixtures(project(":evidence-adapters")))
}

// ArchiveVolumeDbTest validates the published payload against the pinned schema (INV-Q-12746 A); the test fails
// closed when the property is unset.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":evidence-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/evidence/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
    dependsOn(":evidence-contract:generateContractServer")
}
