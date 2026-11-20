// canonical-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
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
