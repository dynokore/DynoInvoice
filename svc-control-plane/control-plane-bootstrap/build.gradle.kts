// control-plane-bootstrap: the Spring Boot application and its composition (TPL-18). All wiring lives here.
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":control-plane-adapters"))
    implementation(project(":control-plane-application"))
    implementation(project(":control-plane-domain"))
    implementation(project(":control-plane-contract"))
    implementation(libs.platform.serviceRuntime)
    implementation(libs.platform.productRuntime)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterFlyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.databasePostgresql)
    implementation(libs.jooq)
    implementation(libs.platform.crypto.api)
    implementation(libs.platform.rulepackKernel.api)
    // the BouncyCastleCms bean of service-runtime's CryptoWiring (DetachedSigner)
    runtimeOnly(libs.platform.crypto)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
}

tasks.withType<Test> { useJUnitPlatform() }
