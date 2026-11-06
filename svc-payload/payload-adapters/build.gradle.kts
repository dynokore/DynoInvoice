import java.security.MessageDigest

// payload-adapters (TPL-01, TPL-11, TPL-12): http, events, persistence and db/migration/.
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
    `java-test-fixtures`
}

// The jars that hold BindingKeyMigration: gst.jooq-codegen installs the signed-binding functions from them before
// the first migration runs (INV-Q-7418 A, platform-build INV-T-7430). Build time only; who installs the functions
// in an installation stays INV-Q-6580.
val bindingKernel: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// The xjc tool (INV-Q-7106 A): a build-time dependency on its own resolvable configuration, on no compile or runtime classpath.
val xjc: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

gstJooqCodegen {
    schema = "payload"
    initScripts.from("src/testFixtures/resources/db/roles.sql")
    bindingSource.from(bindingKernel)
    bindingSysRole = "payload_sys"
    bindingRuntimeRole = "payload_runtime"
}

dependencies {
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel.impl)
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":payload-application"))
    implementation(project(":payload-domain"))
    implementation(project(":payload-contract"))
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.auditLog.impl)
    implementation(libs.platform.configResolver.impl)
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.integrationCanon.impl)
    implementation(libs.saxon.he)
    implementation(libs.jakarta.xml.bind.api)
    implementation(libs.jaxb.runtime)
    xjc(libs.jaxb.xjc)
    xjc(libs.jaxb.runtime) // xjc 4 loads the JAXB implementation from its own class path
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterValidation)
    implementation(libs.swagger.annotations)
    implementation(libs.gstCatalogue)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.databasePostgresql)
    testFixturesImplementation(platform(libs.platform.bom))
    testFixturesImplementation(libs.platform.tenancyKernel.api)
    testFixturesImplementation(libs.platform.tenancyKernel.impl)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.databasePostgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}

// --- INV-T-7113, INV-Q-7106 A: the vendored UBL XSD sets, their checks and the xjc generation ------------------------
// A set is a directory of src/main/xsd/<xsdSetKey>/ with MANIFEST.sha256 (`<sha256>  <path relative to the set>` per XSD
// file). The checks fail closed: a missing, empty or unexpected input is a failure, never a pass.
val xsdRoot = layout.projectDirectory.dir("src/main/xsd")
val xsdSetsTable = layout.projectDirectory.file("xsd-sets.tsv")
val mdePackFixture = layout.projectDirectory.file("src/test/resources/mde/mde-pack-fixture.json")

// The maindoc schemas xjc compiles per set; the set directory is the key of this map.
val ublMaindocs = mapOf("ubl-2.1" to listOf("maindoc/UBL-Invoice-2.1.xsd", "maindoc/UBL-CreditNote-2.1.xsd"))

fun sha256Hex(file: File): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

val verifyXsdManifest =
    tasks.register("verifyXsdManifest") {
        group = "verification"
        description =
            "Every set directory has a MANIFEST.sha256 equal to its XSD files by path set and by SHA-256 (INV-Q-7106 A)."
        val root = xsdRoot.asFile
        doLast {
            val sets = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
            val problems = mutableListOf<String>()
            if (sets.isEmpty()) problems += "no XSD set directory under ${root.path}"
            for (set in sets) {
                val manifest = File(set, "MANIFEST.sha256")
                if (!manifest.isFile) {
                    problems += "${set.name}: no MANIFEST.sha256"
                    continue
                }
                val listed = linkedMapOf<String, String>()
                manifest.readLines().forEachIndexed { index, line ->
                    val match = Regex("^([0-9a-f]{64})  (\\S.*)$").matchEntire(line)
                    when {
                        match == null -> {
                            problems +=
                                "${set.name}: MANIFEST.sha256 line ${index + 1} is not '<sha256>  <path>'"
                        }

                        match.groupValues[2].split('/').any { it.isEmpty() || it == "." || it == ".." } -> {
                            problems +=
                                "${set.name}: MANIFEST.sha256 line ${index + 1} has a path that is not a plain relative path"
                        }

                        listed.put(match.groupValues[2], match.groupValues[1]) != null -> {
                            problems += "${set.name}: ${match.groupValues[2]} is listed twice"
                        }
                    }
                }
                val present =
                    set
                        .walkTopDown()
                        .filter { it.isFile && it.name.lowercase().endsWith(".xsd") }
                        .associateBy { it.relativeTo(set).invariantSeparatorsPath }
                (present.keys - listed.keys).sorted().forEach {
                    problems +=
                        "${set.name}: $it is not listed in MANIFEST.sha256"
                }
                (listed.keys - present.keys).sorted().forEach { problems += "${set.name}: $it is listed but missing" }
                for ((path, digest) in listed) {
                    val file = present[path] ?: continue
                    if (sha256Hex(file) !=
                        digest
                    ) {
                        problems += "${set.name}: $path has another SHA-256 than MANIFEST.sha256"
                    }
                }
            }
            if (problems.isNotEmpty()) {
                throw GradleException("verifyXsdManifest failed:\n" + problems.joinToString("\n") { "  $it" })
            }
        }
    }

val verifyXsdSets =
    tasks.register("verifyXsdSets") {
        group = "verification"
        description =
            "xsd-sets.tsv maps exactly the specification versions of the MDE pack fixture to existing set directories (INV-Q-7106 A)."
        val root = xsdRoot.asFile
        val table = xsdSetsTable.asFile
        val fixture = mdePackFixture.asFile
        doLast {
            val problems = mutableListOf<String>()
            val fixtureVersions = mutableSetOf<String>()
            if (!fixture.isFile) {
                problems += "the fixture ${fixture.path} is missing"
            } else {
                val parsed = groovy.json.JsonSlurper().parse(fixture) as? Map<*, *>
                val rows = parsed?.get("rows") as? List<*>
                if (rows == null) problems += "the fixture has no rows array"
                rows?.forEach { row ->
                    val specs = (row as? Map<*, *>)?.get("specifications") as? List<*>
                    specs?.forEach { spec ->
                        val version = (spec as? Map<*, *>)?.get("specificationVersion") as? String
                        if (version.isNullOrBlank()) {
                            problems += "a fixture row has a blank specificationVersion"
                        } else {
                            fixtureVersions +=
                                version
                        }
                    }
                }
                if (fixtureVersions.isEmpty()) problems += "the fixture lists no specificationVersion"
            }
            val tableVersions = mutableSetOf<String>()
            if (!table.isFile) {
                problems += "the table ${table.path} is missing"
            } else {
                val lines = table.readLines()
                if (lines.isEmpty() || lines[0] != "specificationVersion\txsdSetKey") {
                    problems += "xsd-sets.tsv has another header than 'specificationVersion<TAB>xsdSetKey'"
                }
                val rows = lines.drop(1)
                if (rows.isEmpty()) problems += "xsd-sets.tsv has no row"
                rows.forEachIndexed { index, line ->
                    val cells = line.split('\t')
                    val number = index + 2
                    if (cells.size != 2 || cells.any { it.isBlank() }) {
                        problems += "xsd-sets.tsv line $number needs two non-blank columns"
                        return@forEachIndexed
                    }
                    if (!tableVersions.add(cells[0])) problems += "xsd-sets.tsv lists ${cells[0]} twice"
                    if (!File(root, cells[1]).isDirectory || cells[1].contains('/') || cells[1].contains("..")) {
                        problems +=
                            "xsd-sets.tsv line $number names the set '${cells[1]}', which is no directory under src/main/xsd"
                    }
                }
            }
            (fixtureVersions - tableVersions).sorted().forEach {
                problems +=
                    "specificationVersion $it of the fixture has no row in xsd-sets.tsv"
            }
            (tableVersions - fixtureVersions).sorted().forEach {
                problems +=
                    "xsd-sets.tsv row $it is no specificationVersion of the fixture"
            }
            if (problems.isNotEmpty()) {
                throw GradleException("verifyXsdSets failed:\n" + problems.joinToString("\n") { "  $it" })
            }
        }
    }

val verifyNoGeneratedSources =
    tasks.register("verifyNoGeneratedSources") {
        group = "verification"
        description = "git ls-files payload-adapters lists no build output and no generated UBL class (INV-Q-7106 A)."
        val repoRoot = rootProject.projectDir
        doLast {
            val process =
                ProcessBuilder("git", "ls-files", "payload-adapters")
                    .directory(repoRoot)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("verifyNoGeneratedSources: git ls-files failed:\n$output")
            val offenders =
                output.lines().filter {
                    it.isNotBlank() &&
                        (
                            it.contains("/build/") ||
                                (it.endsWith(".java") && it.contains("/com/dynokore/gst/payload/ubl/"))
                        )
                }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "verifyNoGeneratedSources failed, generated files are tracked:\n" +
                        offenders.joinToString("\n") { "  $it" },
                )
            }
        }
    }

// One generation task per xsdSetKey, into its own package com.dynokore.gst.payload.ubl.<key with '.' as '_'> (bindings.xjb).
val ublGenerationTasks =
    ublMaindocs.map { (setKey, maindocs) ->
        val out = layout.buildDirectory.dir("generated/sources/xjc/$setKey")
        val set = xsdRoot.dir(setKey)
        val task =
            tasks.register<JavaExec>(
                "generateUblJaxb" +
                    setKey.split('-', '.').joinToString("") {
                        it.replaceFirstChar(Char::uppercase)
                    },
            ) {
                group = "build"
                description =
                    "xjc over the UBL XSD set $setKey into build/generated/sources/xjc/$setKey (INV-Q-7106 A)."
                inputs.dir(set)
                outputs.dir(out)
                classpath = xjc
                mainClass.set("com.sun.tools.xjc.XJCFacade")
                javaLauncher.set(
                    javaToolchains.launcherFor(
                        project.extensions
                            .getByType<JavaPluginExtension>()
                            .toolchain,
                    ),
                )
                // the set's checks run first: the XSD files xjc reads are the manifest-verified ones
                dependsOn(verifyXsdManifest)
                doFirst {
                    out
                        .get()
                        .asFile
                        .deleteRecursively()
                        .also { out.get().asFile.mkdirs() }
                }
                argumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-encoding",
                            "UTF-8",
                            "-no-header",
                            "-d",
                            out.get().asFile.path,
                            "-b",
                            set.file("bindings.xjb").asFile.path,
                        ) +
                            maindocs.map { set.file(it).asFile.path }
                    },
                )
            }
        sourceSets.main { java.srcDir(files(out).builtBy(task)) }
        task
    }

tasks.register("generateUblJaxb") {
    group = "build"
    description = "Runs the xjc generation of every UBL XSD set."
    dependsOn(ublGenerationTasks)
}

tasks.named("compileKotlin") { dependsOn(ublGenerationTasks) }
tasks.named("compileJava") { dependsOn(ublGenerationTasks) }

tasks.named("check") { dependsOn(verifyXsdManifest, verifyXsdSets, verifyNoGeneratedSources) }
