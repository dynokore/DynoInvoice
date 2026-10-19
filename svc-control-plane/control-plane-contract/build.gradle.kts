// control-plane-contract: generated from platform-contracts/control-plane (STK-46, TPL-01); no hand-written logic.
// The pinned platform-contracts bundle (gradle/libs.versions.toml) is staged below as a build-local Maven layout, which
// gst.contract-server resolves `platform-contracts:control-plane:+` from (settings.gradle.kts).

plugins {
    id("gst.contract-server")
    `java-library`
}

val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
}

val stagedRepo = layout.buildDirectory.dir("contracts-repo")
val bundleVersion = libs.versions.platformContractsBundle

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
    val svc = "control-plane"
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
    // The OpenAPI document's `../service-runtime/...` $ref resolves beside the artefact.
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

contractServer {
    generatorSource("platform-contracts:control-plane:openapi.yaml")
}

dependencies {
    api(platform(libs.springBoot.dependencies))
    api(libs.spring.web)
    api(libs.spring.context)
    api(libs.jackson.databind)
    api(libs.jackson.annotations)
    api(libs.jackson.moduleKotlin)
    api(libs.jakarta.validationApi)
    api(libs.swagger.annotations)
}
