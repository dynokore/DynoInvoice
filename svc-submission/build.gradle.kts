// build.gradle.kts — svc-submission root (TPL-01; INV-T-5964). Only what every module shares.
allprojects {
    group = "com.dynokore.gst.submission"
    version = "0.1.0"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
