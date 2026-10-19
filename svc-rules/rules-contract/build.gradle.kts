// rules-contract/build.gradle.kts — TPL-01, STK-46, API-01 (INV-T-4965).
//
// Generated from platform-contracts/rules/ (openapi.yaml, asyncapi.yaml, schemas/), never hand-written (TPL-01 §2.1):
// gst.contract-server generates the Spring server interfaces and DTOs from openapi.yaml; the AsyncAPI document and the
// payload and pack-content schemas of the pinned bundle are staged beside them as resources
// (`contract/asyncapi.yaml`, `contract/schemas/*.schema.json`) so the service's gates and its per-channel payload tests
// read the same bytes the contract task INV-T-4911 settled. The contract is supplemented upstream, never here.

plugins {
    id("gst.contract-server")
}

// ---- the pinned platform-contracts bundle as a build-local Maven layout (INV-Q-3852) ----

val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
}

val stagedRepo = layout.buildDirectory.dir("contracts-repo")
val bundleVersion = libs.versions.platformContractsBundle
val bundleService = "rules"

// gst.contract-server resolves its source while Gradle builds the task graph, so the layout must exist before any
// task runs: it is staged while this project is configured, once per bundle version (the marker file), from the
// bundle Gradle downloaded and cached like any other dependency.
fun stageContracts() {
    val version = bundleVersion.get()
    val root = stagedRepo.get().asFile
    val marker = File(root, ".bundle-version")
    if (marker.isFile && marker.readText() == version) return
    root.deleteRecursively()
    val unpacked = File(root, ".bundle")
    project.copy {
        from(project.zipTree(contractsBundle.singleFile))
        into(unpacked)
    }
    val moduleDir = File(root, "platform-contracts/$bundleService")
    val svcDir = File(unpacked, bundleService)
    check(svcDir.isDirectory) { "the platform-contracts bundle $version has no $bundleService/ directory" }
    val versionDir = File(moduleDir, version)
    svcDir.copyRecursively(versionDir, overwrite = true)
    svcDir.walkTopDown().filter { it.isFile }.forEach { file ->
        val rel = file.relativeTo(svcDir).invariantSeparatorsPath
        val classifier = rel.substringBeforeLast('.', rel).replace('/', '-')
        val ext = rel.substringAfterLast('.', "")
        file.copyTo(File(versionDir, "$bundleService-$version-$classifier.$ext"), overwrite = true)
    }
    // An OpenAPI document's `../service-runtime/...` $ref resolves beside the artefact (codegen-self-test).
    File(unpacked, "service-runtime").copyRecursively(File(moduleDir, "service-runtime"), overwrite = true)
    File(moduleDir, "maven-metadata.xml").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>platform-contracts</groupId>
          <artifactId>$bundleService</artifactId>
          <versioning>
            <latest>$version</latest>
            <release>$version</release>
            <versions><version>$version</version></versions>
          </versioning>
        </metadata>
        """.trimIndent(),
    )
    File(versionDir, "$bundleService-$version.pom").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>platform-contracts</groupId>
          <artifactId>$bundleService</artifactId>
          <version>$version</version>
          <packaging>pom</packaging>
        </project>
        """.trimIndent(),
    )
    marker.writeText(version)
}

stageContracts()

contractServer {
    generatorSource("platform-contracts:rules:openapi.yaml") // source of truth lives in platform-contracts (OW1)
}

// The AsyncAPI document and the schemas of the same bundle, as resources of this module.
val stageContractResources =
    tasks.register<Sync>("stageContractResources") {
        group = "contract"
        description = "Stages asyncapi.yaml and schemas/ of the pinned platform-contracts bundle as module resources."
        val bundleRules = stagedRepo.map { it.dir(".bundle/$bundleService") }
        from(bundleRules) {
            include("asyncapi.yaml", "schemas/*.json")
        }
        into(layout.buildDirectory.dir("generated/contract-resources/contract"))
    }

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/contract-resources"))
    }
}

tasks.named("processResources") { dependsOn(stageContractResources) }

dependencies {
    implementation(platform(libs.springBoot.dependencies))
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterValidation)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.swagger.annotations)
}
