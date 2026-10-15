// calendar-and-periods/build.gradle.kts — STK-60 L2 module (INV-MOD-2742, ARC-16, FR-L.6; INV-T-4963).
//
// Fiscal and operational periods, business-day calendars, deadline derivation and period readiness gating. One
// Gradle project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `calendar-and-periods-api`) — value types, ports and the pure readiness gate,
//     framework-free (ARC-16); the only half another module or a service may depend on.
//   - `src/impl` (published as `calendar-and-periods`) — the pack-driven generator and due-date deriver, built on
//     top of `src/main`'s output; embedded by tenancy-svc, canonical-svc and submission-svc.
// An L2 module uses L1 modules only; this one needs none (pure date arithmetic over pack data).

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
