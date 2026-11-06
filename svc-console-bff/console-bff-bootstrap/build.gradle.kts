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
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    // the /metrics endpoint of the management server (API-33): the Prometheus scrape format
    runtimeOnly(libs.micrometer.registryPrometheus)
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
    testImplementation(libs.snakeyaml)
    testImplementation(testFixtures(project(":console-bff-adapters")))
    constraints {
        implementation(libs.tomcat.embedCore)
        implementation(libs.tomcat.embedEl)
        implementation(libs.tomcat.embedWebsocket)
    }
}
