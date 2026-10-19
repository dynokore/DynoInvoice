// The plugins of every module are put on one class loader here (apply false), so a Gradle service shared by two of
// them (Spotless' task service) is one class: without this the Spring Boot plugin of submission-bootstrap and the
// gst.* plugins of its siblings load in different class loaders and spotless cannot create its tasks.
plugins {
    id("gst.kotlin-base") apply false
    id("gst.domain") apply false
    id("gst.application") apply false
    id("gst.adapters") apply false
    id("gst.bootstrap") apply false
    id("gst.contract-server") apply false
    id("gst.jooq-codegen") apply false
    id("org.springframework.boot") apply false
}

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
