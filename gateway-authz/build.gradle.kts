// build.gradle.kts — gateway-authz (INV-T-4056; INV-ADR-4051, owner ruling INV-Q-4046 A).
//
// The api-gateway's token-validation and rate-limit container (INV-MOD-2752): a Spring Boot application on
// platform-libs service-runtime (TPL-18) with no database and no business logic, one image (STK-53) that the
// api-gateway chart pins. One Gradle project: there is no domain or contract of its own to split out (ARC §2.2 is
// for services; this is a platform component's container).

plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

group = "com.dynokore.gst"

// STK-72: every configuration is locked (gradle.lockfile); a resolution outside the lock fails the build.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(platform(libs.resilience4j.bom))

    // TPL-18: service-runtime is the composition root. Its impl artefact is published without a dependency list
    // (platform-libs service-runtime/build.gradle.kts), so the modules and libraries it loads are named here.
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.configResolver.api)
    implementation(libs.platform.auditLog.api)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.timelimiter)

    // STK-05: servlet stack for the health and Prometheus endpoints (API-33); STK-26/STK-27: Nimbus JOSE through
    // Spring Security for signature and expiry; STK-08: Jackson 3.
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springSecurity.oauth2Jose)
    implementation(libs.micrometer.prometheus)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.moduleKotlin)

    // INV-Q-4077: Spring Boot's gRPC server (grpc-java and Spring gRPC at the Boot BOM's versions) on grpc-netty-shaded,
    // whose epoll transport binds the Unix socket; Envoy's envoy.service.auth.v3 stubs. No other gRPC library.
    implementation(libs.springBoot.starterGrpcServer) {
        exclude(group = "io.grpc", module = "grpc-netty")
    }
    implementation(libs.grpc.nettyShaded)
    // The stubs only carry the SOURCE-retention javax.annotation.Generated; the CDDL-1.1/GPL-2.0-CPE artefact is
    // not a runtime need and fails the STK-66 licence policy, so it is excluded (no licence exception).
    implementation(libs.envoy.api) {
        exclude(group = "javax.annotation", module = "javax.annotation-api")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.mockk)
    testImplementation(libs.springBoot.starterTest)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, as platform-libs' modules
}

// STK-53: the builder, run image and buildpacks come from gst.bootstrap (platform-build's catalogue, pinned by digest);
// this block sets only the JVM version and the AOT cache. SOURCE_DATE_EPOCH comes from ci/image's build-image job (STK-72).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    environment.set(
        mapOf(
            "BP_JVM_VERSION" to "25",
            "BP_JVM_AOTCACHE_ENABLED" to "true",
        ),
    )
}
