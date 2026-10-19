// control-plane-domain: pure Kotlin (ARC-03). The Konsist layer rule refuses a Spring or jOOQ import here and passes a
// java.sql or javax.sql import (owner answer INV-Q-5331 (D)).
plugins {
    id("gst.domain")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
}

tasks.withType<Test> { useJUnitPlatform() }
