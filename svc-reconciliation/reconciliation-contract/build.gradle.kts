// reconciliation-contract (TPL-01, TPL-13): the Spring server interfaces and DTOs generated from
// platform-contracts/reconciliation/openapi.yaml (STK-46). asyncapi.yaml and schemas/ are staged from the same
// pinned bundle for the operations gate and the payload tests of reconciliation-adapters.
plugins {
    id("gst.contract-server")
}

contractServer {
    generatorSource("platform-contracts:reconciliation:openapi.yaml")
}

val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
    implementation(platform(libs.springBoot.dependencies))
    // Spring Boot 4.1.1 manages Jackson 3.1.5 (HIGH CVEs); the catalogue's jackson version is the one the service runs.
    implementation(platform(libs.jackson.bom))
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterValidation)
    implementation(libs.swagger.annotations)
    implementation(libs.jackson.moduleKotlin)
    constraints {
        implementation(libs.tomcat.embedCore)
        implementation(libs.tomcat.embedEl)
        implementation(libs.tomcat.embedWebsocket)
    }
}

// gst.contract-server resolves `platform-contracts:reconciliation:+` while the task graph is built, so the layout
// is staged while this project is configured, once per bundle version (the marker file).
fun stageContracts() {
    val version = libs.versions.platformContractsBundle.get()
    val root =
        layout.buildDirectory
            .dir("contracts-repo")
            .get()
            .asFile
    val marker = File(root, ".bundle-version")
    if (marker.isFile && marker.readText() == version) return
    root.deleteRecursively()
    val unpacked = File(root, ".bundle")
    copy {
        from(zipTree(contractsBundle.singleFile))
        into(unpacked)
    }
    val svc = "reconciliation"
    val moduleDir = File(root, "platform-contracts/$svc")
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
    // An OpenAPI document's `../service-runtime/...` $ref resolves beside the artefact.
    File(unpacked, "service-runtime").copyRecursively(File(moduleDir, "service-runtime"), overwrite = true)
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
    marker.writeText(version)
}
stageContracts()

// The contract document ships as a resource (gst/contracts/reconciliation/openapi.yaml), as the other services ship theirs:
// the test of the caller check (INV-AC-8455) reads the x-sync-edge of the document the code was generated from.
val contractResources = layout.buildDirectory.dir("contract-resources")
val stageContractResources =
    tasks.register<Sync>("stageContractResources") {
        from(layout.buildDirectory.dir("contracts-repo/.bundle/reconciliation")) {
            include("openapi.yaml")
            into("gst/contracts/reconciliation")
        }
        into(contractResources)
    }
sourceSets.main { resources.srcDir(contractResources) }
tasks.named("processResources") { dependsOn(stageContractResources) }
