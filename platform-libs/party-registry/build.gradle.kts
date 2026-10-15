// party-registry/build.gradle.kts — STK-60 L2 module (INV-MOD-2740, ARC-16, FR-L.6; INV-T-4964).
//
// Legal entities, scheme-qualified identifiers with provenance, addresses, the supplier identity triple and the
// scheme validation hooks. One Gradle project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `party-registry-api`) — the pure model and the hook ports, no I/O, no framework.
//   - `src/impl` (published as `party-registry`) — PartyChecks, composing the NA rule and the injected hook;
//     embedded by masterdata-svc.
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
    // The compile-rejection test (INV-REQ-803) runs the Kotlin compiler the build already resolves; no new dependency.
    val compilerClasspath = configurations.named("kotlinCompilerClasspath")
    val apiClasses = sourceSets["main"].output.classesDirs
    val stdlib = configurations.named("testRuntimeClasspath")
    doFirst {
        systemProperty("partyregistry.compiler.classpath", compilerClasspath.get().asPath)
        systemProperty("partyregistry.api.classes", apiClasses.asPath)
        systemProperty(
            "partyregistry.stdlib",
            stdlib
                .get()
                .files
                .first { it.name.startsWith("kotlin-stdlib-") && !it.name.contains("sources") }
                .path,
        )
    }
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
