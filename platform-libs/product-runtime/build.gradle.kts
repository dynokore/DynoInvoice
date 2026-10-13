// product-runtime/build.gradle.kts — STK-60 L3 module (ARC-16, FR-L.6; INV-T-2860 / T16, INV-T-2876 / T32).
//
// One Gradle project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `product-runtime-api`) — the product's configuration values (replica feeds and
//     fallbacks, the platform-event catalogue, the train's fence participants), framework-free (ARC-16).
//   - `src/impl` (published as `product-runtime`) — the bindings every back-end service embeds through
//     `ProductRuntimeConfiguration` (TPL-18): the gst-catalogue VariationPoint adapter and the DelegationProbe for
//     config-resolver (INV-Q-3119, INV-Q-3136), the structure and authority feed handlers and the tenancy-svc and
//     access-svc fallbacks for service-runtime's replicas (AD-26, INV-Q-3259).
//
// Generated contract code (coordinator ruling INV-Q-3852; STK-23, STK-74, ARC-08): `stageContracts()` lays the
// pinned platform-contracts bundle out as a build-local Maven repository (as platform-contracts' codegen-self-test
// does), from which the six `product-runtime-*` projects under `contracts/` generate the event payload types
// (gst.event-types) and the fallback clients (gst.contract-client). Their classes ship inside the impl artefact.

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
    create("impl")
    create("implTest")
}

// ---- the pinned platform-contracts bundle as a build-local Maven layout (INV-Q-3852) ----

val contractsBundle by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    contractsBundle(variantOf(libs.platformContracts.bundle) { artifactType("zip") })
}

/** The services whose contracts product-runtime generates from (tenancy, access, service-runtime). */
val stagedServices = listOf("tenancy", "access", "service-runtime")
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

// ---- dependencies ----

val generatedContracts =
    listOf(
        ":product-runtime-tenancy-client",
        ":product-runtime-access-client",
        ":product-runtime-structure-events",
        ":product-runtime-profile-events",
        ":product-runtime-authority-events",
        ":product-runtime-policy-events",
    )

evaluationDependsOn(":service-runtime")
evaluationDependsOn(":outbox-and-queue")
evaluationDependsOn(":tenancy-kernel")
evaluationDependsOn(":config-resolver")
generatedContracts.forEach { evaluationDependsOn(it) }

val serviceRuntimeImpl = project(":service-runtime").the<SourceSetContainer>()["impl"].output
val outboxImplJar = project(":outbox-and-queue").tasks.named("implJar")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")
// config-resolver's impl, so the TPL-18 WiringTest builds config-resolver's ports over the catalogue.
val configResolverImplJar = project(":config-resolver").tasks.named("implJar")

dependencies {
    add("implImplementation", sourceSets["main"].output)
    add("implTestImplementation", sourceSets["main"].output)
    add("implTestImplementation", sourceSets["impl"].output)

    listOf("implImplementation", "implTestImplementation").forEach { config ->
        add(config, project(":service-runtime"))
        add(config, serviceRuntimeImpl)
        add(config, project(":config-resolver"))
        add(config, project(":outbox-and-queue"))
        add(config, project(":tenancy-kernel"))
        add(config, libs.gstCatalogue)
        generatedContracts.forEach { add(config, project(it)) }
        add(config, platform(libs.springBoot.dependencies))
        add(config, libs.springBoot.starterActuator)
        add(config, libs.springWeb)
        add(config, libs.jackson.databind)
        add(config, libs.jackson.moduleKotlin)
    }
    add("implTestImplementation", files(outboxImplJar))
    add("implTestImplementation", files(tenancyKernelImplJar))
    add("implTestImplementation", files(configResolverImplJar))
    // service-runtime impl's own dependencies, which a service gets from its POM (the TPL-18 WiringTest).
    add("implTestRuntimeOnly", libs.jakarta.servletApi)
    add("implTestRuntimeOnly", libs.springSecurity.oauth2Jose)
    // The TPL-18 WiringTest's DataSource from Spring Boot's DataSource auto-configuration on a lazy Hikari pool with
    // no database (INV-Q-4196 (A), coordinator ruling INV-Q-4436): spring-boot-jdbc, HikariCP and the driver.
    add("implTestRuntimeOnly", libs.springBoot.starterJdbc)
    add("implTestRuntimeOnly", libs.postgresql)
    add("implTestImplementation", libs.springBoot.test)
    add("implTestImplementation", libs.assertj.core)

    listOf("testImplementation", "implTestImplementation").forEach { config ->
        add(config, platform(libs.junit.bom))
        add(config, libs.junit.jupiter)
        add(config, libs.kotest.runnerJunit5)
        add(config, libs.kotest.assertionsCore)
        add(config, libs.mockk)
        add("${config.removeSuffix("Implementation")}RuntimeOnly", libs.junit.platformLauncher)
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
}

val implTestTask =
    tasks.register<Test>("implTest") {
        group = "verification"
        description = "Runs the impl half's tests: catalogue adapter, delegation probe, feeds, fallbacks, wiring."
        testClassesDirs = sourceSets["implTest"].output.classesDirs
        classpath = sourceSets["implTest"].runtimeClasspath
        useJUnitPlatform()
        jvmArgs("-Duser.timezone=UTC")
        systemProperty("user.language", "de")
        systemProperty("user.country", "DE")
        // The contract tests read the staged bundle, the same files the generators used.
        systemProperty("productruntime.contracts", stagedRepo.get().asFile.absolutePath)
        systemProperty("productruntime.contractsVersion", bundleVersion.get())
    }

tasks.named("check") {
    dependsOn(implTestTask)
}

// gst.contract-client generates the openapi-generator runtime into `org.openapitools.client.infrastructure` for every
// client project (platform-build sets only the api and model packages), so the tenancy and access clients each
// compile the same classes. The impl artefact ships exactly one copy, the first client's, after
// `verifyClientInfrastructure` has proved the generated sources of every copy identical (their class files differ
// only in the Kotlin module name each records, as the classes are compiled in two projects).
val clientProjects = generatedContracts.filter { it.endsWith("-client") }
val clientInfrastructure = "org/openapitools/client/infrastructure"
val generatedClientSources = "generated/contract-client/src/main/kotlin"

val verifyClientInfrastructure =
    tasks.register("verifyClientInfrastructure") {
        group = "verification"
        description = "Fails unless every generated client's openapi-generator infrastructure source is identical."
        val dirs =
            clientProjects.map {
                project(
                    it,
                ).layout.buildDirectory.dir("$generatedClientSources/$clientInfrastructure")
            }
        clientProjects.forEach { dependsOn("$it:compileKotlin") }
        dirs.forEach { inputs.dir(it) }
        doLast {
            fun contents(dir: File): Map<String, String> =
                dir.walkTopDown().filter { it.isFile }.associate {
                    it.relativeTo(dir).invariantSeparatorsPath to
                        it.readText()
                }
            val copies = dirs.map { contents(it.get().asFile) }
            check(
                copies.first().isNotEmpty(),
            ) { "no generated $clientInfrastructure sources in ${clientProjects.first()}" }
            copies.drop(1).forEachIndexed { i, copy ->
                check(copy == copies.first()) {
                    "${clientProjects[i + 1]}'s generated $clientInfrastructure differs from " +
                        "${clientProjects.first()}'s; " +
                        "the impl artefact can ship only one copy"
                }
            }
        }
    }

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        // A separate local output directory, as tenancy-kernel's `implJar`: the api `jar` writes the same file name.
        destinationDirectory.set(layout.buildDirectory.dir("libs-impl"))
        dependsOn(verifyClientInfrastructure)
        from(sourceSets["impl"].output)
        // The generated contract types and clients live only in this artefact (INV-Q-3852), with one copy of the
        // shared client infrastructure (above).
        generatedContracts.forEach { path ->
            from(project(path).the<SourceSetContainer>()["main"].output) {
                if (path in clientProjects.drop(1)) exclude("$clientInfrastructure/**")
            }
        }
    }

// The merge-request pipeline runs `test` and `implTest`, not `check` or `publish`: implTest also builds both
// published artefacts and their POMs, so a jar that cannot be assembled fails the merge request, not the main publish.
implTestTask.configure {
    dependsOn(implJar, tasks.named("jar"))
    dependsOn(tasks.matching { it.name.startsWith("generatePomFileFor") })
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

// Two Jar tasks publish from this one project (`jar` for `api`, `implJar` for `impl`); every publish-related
// task's own validation otherwise flags an undeclared dependency on whichever Jar task is not its own.
tasks.matching { it.name.startsWith("generateMetadataFileFor") || it.name.startsWith("publish") }.configureEach {
    dependsOn(implJar, tasks.named("jar"))
}
