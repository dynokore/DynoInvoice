// build.gradle.kts — gateway-authz (INV-T-6522; INV-ADR-4051, owner ruling INV-Q-4046 A).
//
// The api-gateway's token-validation and rate-limit container (INV-MOD-2752). This is the build on platform-build's
// conventions with dependency locking and strict verification (STK-72); the service's own dependencies and code, and
// the image, come with INV-T-4056. The Paketo pins (STK-53) come from gst.bootstrap, so there is no bootBuildImage
// block here.

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
    implementation(platform(libs.springBoot.dependencies))
}
