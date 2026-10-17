// document-lifecycle/build.gradle.kts — STK-60 L2 module (INV-MOD-2741, ARC-16, FR-L.6; INV-T-5938).
//
// Versioned document, state model as data, supersession rules and the in-flight guard. One Gradle project, one
// version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `document-lifecycle-api`) — the state model, versions, supersession rules and the
//     pure document operations, framework-free (ARC-16).
//   - `src/impl` (published as `document-lifecycle`) — DocumentLifecycle, the facade an embedding service
//     instantiates with its own model; embedded by canonical-svc, payload-svc and submission-svc.
// An L2 module uses L1 modules only; this one needs none.

import gst.build.Layer

plugins {
    id("gst.library")
    `maven-publish`
}

gstLibrary {
    layer = Layer.L2
}

version = file("version.txt").readText().trim()

sourceSets {
    create("impl") {
        kotlin.srcDir("src/impl/kotlin")
        resources.srcDir("src/impl/resources")
    }
}

dependencies {
    // The impl half compiles and runs against the api half's output, in this same project.
    add("implImplementation", sourceSets["main"].output)

    testImplementation(sourceSets["impl"].output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.platform.konsistRules) // the architecture tests (TPL-14) only
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline
}

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        destinationDirectory.set(layout.buildDirectory.dir("libs-impl")) // as outbox-and-queue: no clash with `jar`
        from(sourceSets["impl"].output)
    }

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "${project.name}-api"
            from(components["java"]) // `main` only
        }
        create<MavenPublication>("impl") {
            artifactId = project.name
            artifact(implJar)
        }
    }
}

// Two Jar tasks publish from this one project (`jar` for `api`, `implJar` for `impl`); see config-resolver.
tasks.matching { it.name.startsWith("generateMetadataFileFor") || it.name.startsWith("publish") }.configureEach {
    dependsOn(implJar, tasks.named("jar"))
}
