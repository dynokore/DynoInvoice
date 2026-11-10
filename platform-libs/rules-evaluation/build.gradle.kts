// rules-evaluation/build.gradle.kts — STK-60 L3 module (ARC-16, FR-L.6; INV-T-19454, INV-Q-8719).
//
// The pure rule evaluation that rules-svc and control-plane-svc both embed (INV-REQ-775): per-line coding over the
// code catalogue, the mapping and the scenario data, and the decoders of the pack contents JSON they are read from.
// It reads no clock, store, network or Spring type. Slice 1 of 3: the coding, the decoders, TenantMapping and
// EvaluationResult.
//
// One Gradle project, one version, one changelog, published as the api/impl pair of the sibling modules; the code
// lives in `src/main` (`rules-evaluation-api`) and `src/impl` is empty in this slice.
//
// The decoders' tests judge each sample by the published JSON schemas as well: they are staged from the pinned
// platform-contracts bundle (as product-runtime does) as a test resource, never copied into the repository.

import gst.build.Layer

plugins {
    id("gst.library")
    `maven-publish`
}

gstLibrary {
    layer = Layer.L3
}

version = file("version.txt").readText().trim()

sourceSets {
    create("impl") {
        kotlin.srcDir("src/impl/kotlin")
        resources.srcDir("src/impl/resources")
    }
}

val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

val stageContractSchemas =
    tasks.register<Sync>("stageContractSchemas") {
        description = "Stages the rules schemas of the pinned platform-contracts bundle as a test resource."
        from(
            provider { zipTree(contractsBundle.singleFile) },
        ) {
            include("rules/schemas/*.json")
            eachFile { path = "contract/schemas/$name" }
            includeEmptyDirs = false
        }
        into(layout.buildDirectory.dir("generated/contract-test-resources"))
    }

sourceSets.test {
    resources.srcDir(stageContractSchemas)
}

dependencies {
    api(project(":rulepack-kernel"))
    api(project(":tenancy-kernel"))
    api(libs.gstCatalogue) // ErrorCode and Severity are part of LineCoding
    implementation(libs.jackson.databind) // STK-08: the pack contents decoders

    add("implImplementation", sourceSets["main"].output)

    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })

    testImplementation(sourceSets["impl"].output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.jsonSchemaValidator) // judges each decoder sample by the published schema
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
