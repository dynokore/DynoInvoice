// payload-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":payload-adapters"))
    implementation(project(":payload-application"))
    implementation(project(":payload-domain"))
    implementation(project(":payload-contract"))
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
    testImplementation(testFixtures(project(":payload-adapters")))
    testImplementation(libs.platform.serviceRuntime.api) {
        capabilities { requireCapability("com.dynokore.platform:service-runtime-test-fixtures") }
    }
}

// PayloadReadyOutboxDbTest reads the published schema from the pinned contract bundle staged by payload-contract.
tasks.withType<Test>().configureEach {
    val contractDir =
        project(":payload-contract").layout.buildDirectory.dir(
            "contracts-repo/platform-contracts/payload/${libs.versions.platformContractsBundle.get()}",
        )
    systemProperty("gst.contract.dir", contractDir.get().asFile.path)
}
