// access-domain — TPL-01, ARC-03 (INV-T-4934): pure Kotlin. A java.sql or javax.sql type may be named here
// (owner answer INV-Q-5331 (D)); Spring, jOOQ and Jackson may not.
plugins {
    id("gst.domain")
}

dependencies {
    implementation(platform(libs.platform.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
