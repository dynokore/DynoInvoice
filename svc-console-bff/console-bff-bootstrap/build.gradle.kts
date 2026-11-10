// console-bff-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(platform(libs.jackson.bom))
    implementation(project(":console-bff-adapters"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterJooq)
    implementation(libs.jooq)
    implementation(project(":console-bff-application"))
    implementation(project(":console-bff-domain"))
    runtimeOnly(libs.postgresql)
    // the /metrics endpoint of the management server (API-33): the Prometheus scrape format
    runtimeOnly(libs.micrometer.registryPrometheus)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.snakeyaml)
    testImplementation(testFixtures(project(":console-bff-adapters")))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.platform.tenancyKernel.api)
    // the fixtures are published under the capability service-runtime-test-fixtures on the service-runtime-api module,
    // so testFixtures(libs.platform.serviceRuntime.api) (capability service-runtime-api-test-fixtures) does not resolve
    testImplementation(libs.platform.serviceRuntime.api) {
        capabilities { requireCapability("com.dynokore.platform:service-runtime-test-fixtures") }
    }
    constraints {
        implementation(libs.tomcat.embedCore)
        implementation(libs.tomcat.embedEl)
        implementation(libs.tomcat.embedWebsocket)
    }
}
