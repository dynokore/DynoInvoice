// tenancy-contract — generated from platform-contracts/tenancy/ (TPL-01, STK-46); no hand-written logic.
//
// The pinned platform-contracts bundle is laid out as a build-local Maven repository (as platform-libs'
// product-runtime does, INV-Q-3852), from which gst.contract-server resolves `platform-contracts:tenancy:+`.
plugins { id("gst.contract-server") }

val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
    implementation(platform(libs.springBoot.dependencies))
    implementation(libs.springBoot.starterWeb)
    implementation(libs.jakarta.validationApi)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.moduleKotlin)
}

/** The services whose contracts this module stages (tenancy and the service-runtime documents its $refs reach). */
val stagedServices = listOf("tenancy", "service-runtime")
val stagedRepo = layout.buildDirectory.dir("contracts-repo")
val bundleVersion = libs.versions.platformContractsBundle

// gst.event-types and gst.contract-client resolve their source while Gradle builds the task graph, so the layout
// must exist before any task runs: it is staged while this project is configured, once per bundle version (the
// marker file), from the bundle Gradle downloaded and cached like any other dependency.
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
    stagedServices.forEach { svc -> stageService(unpacked, File(root, "platform-contracts/$svc"), svc, version) }
    marker.writeText(version)
}

fun stageService(
    unpacked: File,
    moduleDir: File,
    svc: String,
    version: String,
) {
    val svcDir = File(unpacked, svc)
    check(svcDir.isDirectory) { "the platform-contracts bundle $version has no $svc/ directory" }
    val versionDir = File(moduleDir, version)
    svcDir.copyRecursively(versionDir, overwrite = true)
    svcDir.walkTopDown().filter { it.isFile }.forEach { file ->
        val rel = file.relativeTo(svcDir).invariantSeparatorsPath
        val classifier = rel.substringBeforeLast('.', rel).replace('/', '-')
        val ext = rel.substringAfterLast('.', "")
        file.copyTo(File(versionDir, "$svc-$version-$classifier.$ext"), overwrite = true)
    }
    // An OpenAPI document's `../service-runtime/...` $ref resolves beside the artefact (codegen-self-test).
    if (svc != "service-runtime") {
        File(unpacked, "service-runtime").copyRecursively(File(moduleDir, "service-runtime"), overwrite = true)
    }
    File(moduleDir, "maven-metadata.xml").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>platform-contracts</groupId>
          <artifactId>$svc</artifactId>
          <versioning>
            <latest>$version</latest>
            <release>$version</release>
            <versions><version>$version</version></versions>
          </versioning>
        </metadata>
        """.trimIndent(),
    )
    File(versionDir, "$svc-$version.pom").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>platform-contracts</groupId>
          <artifactId>$svc</artifactId>
          <version>$version</version>
          <packaging>pom</packaging>
        </project>
        """.trimIndent(),
    )
}

stageContracts()

contractServer { generatorSource("platform-contracts:tenancy:openapi.yaml") }
