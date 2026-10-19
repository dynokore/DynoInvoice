// acceptance — the test-only project of TPL-01: tests traced to Keel criteria by key (not one of the five
// architectural modules ARC-10 counts).
plugins { id("gst.kotlin-base") }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}
