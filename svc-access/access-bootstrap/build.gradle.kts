// access-bootstrap — TPL-01, TPL-18 (INV-T-4934): the composition root and the service's configuration.
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":access-adapters"))
    implementation(project(":access-contract"))
    implementation(project(":access-application"))
    implementation(project(":access-domain"))
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.gstCatalogue)
    implementation(libs.jooq)
    implementation(libs.jackson.databind)
    implementation(libs.jakartaServlet)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.platform.tenancyJooq.impl)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.springBoot.starterTest)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
    testImplementation(libs.platform.productRuntime.impl)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // CI-FAST: the 750 bootstrap tests ran in one JVM (about 560 s of the unit-test job on a merge request, where the
    // test task is not restored from the build cache). The test classes share nothing across JVMs: BoundaryFixture
    // starts one PostgreSQL per JVM, the identity-provider simulators are started per JVM on random host ports and no
    // class writes a shared file. The CI test policy (platform-build gst-test-policy) sets the real fork count from
    // the job container (CPUs, memory, cap 4); locally this runs 3 forks at most.
    maxParallelForks = 3
}
