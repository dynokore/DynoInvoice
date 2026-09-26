import gst.catalogue.BlockDispositionChecker
import gst.catalogue.CatalogueArtefact
import gst.catalogue.ErrorCodeYamlParser
import gst.catalogue.KotlinEmitter
import gst.catalogue.StabilityChecker

// gst.catalogue-generator.gradle.kts — INV-T-2851 (T7), STK-61, CS-13.
//
// Applied to the `gst-catalogue` module only (a single consumer, unlike build-logic's
// gst.* plugins): reads catalogue/errors/error-codes.yaml (vendored, pinned-commit
// copy of invoice-docs' model/architecture/error-codes.yaml) and generates
// ErrorCode.kt plus the catalogue artefact JSON, then runs the two acceptance checks
// (INV-AC-2170 stability, INV-AC-2173 BLOCK disposition) as part of `check`.

// No version: resolves from generator/build.gradle.kts's own classpath dependency, the
// same technique gst.kotlin-base.gradle.kts uses in platform-build/build-logic. Only
// needed so this script gets `sourceSets`/`tasks.named("compileKotlin")`'s type-safe
// accessors at compile time — gst-catalogue/build.gradle.kts's own `kotlin("jvm")
// version "2.4.20"` is what actually governs its compilation.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val sourceYaml = rootProject.layout.projectDirectory.file("errors/error-codes.yaml").asFile
val sourceCommit = ErrorCodeYamlParser.readPinnedCommit(sourceYaml) // read from the vendored file's own header, never duplicated
val catalogueVersion = providers.gradleProperty("gstCatalogueVersion").getOrElse("1.0.0")

val generatedKotlinDir = layout.buildDirectory.dir("generated/gst-catalogue/kotlin")
val artefactFile = layout.buildDirectory.file("generated/gst-catalogue/gst-catalogue-error-codes-$catalogueVersion.json")
val previousTrainArtefact = rootProject.layout.projectDirectory.file("errors/previous-train-catalogue.json").asFile

val generateErrorCodeCatalogue =
    tasks.register("generateErrorCodeCatalogue") {
        group = "catalogue"
        description = "STK-61/CS-13: generates ErrorCode.kt and the catalogue artefact JSON from error-codes.yaml."
        inputs.file(sourceYaml)
        outputs.dir(generatedKotlinDir)
        outputs.file(artefactFile)
        doLast {
            val rows = ErrorCodeYamlParser.parse(sourceYaml)
            val outDir = generatedKotlinDir.get().asFile.resolve("com/dynokore/gst/catalogue")
            outDir.mkdirs()
            outDir.resolve("ErrorCode.kt").writeText(
                KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, sourceCommit),
            )
            val artefact = artefactFile.get().asFile
            artefact.parentFile.mkdirs()
            artefact.writeText(CatalogueArtefact.render(rows, catalogueVersion, sourceCommit))
        }
    }

val checkCatalogueStability =
    tasks.register("checkCatalogueStability") {
        group = "verification"
        description = "INV-AC-2170: no code changes its number or condition since the previous published train."
        dependsOn(generateErrorCodeCatalogue)
        inputs.file(artefactFile)
        doLast {
            if (!previousTrainArtefact.exists()) {
                // First train, or genuinely no earlier one to compare against — the same
                // "a new file has nothing to compare against and is skipped, not failed"
                // convention platform-contracts' own oasdiff-breaking-gate already uses.
                logger.lifecycle(
                    "gst-catalogue: no previous train artefact at " +
                        "${previousTrainArtefact.relativeTo(rootProject.projectDir)} — stability check skipped " +
                        "(nothing published yet to compare against).",
                )
                return@doLast
            }
            val previous = StabilityChecker.readArtefact(previousTrainArtefact)
            val current = StabilityChecker.readArtefact(artefactFile.get().asFile)
            val violations = StabilityChecker.check(previous, current)
            if (violations.isNotEmpty()) {
                throw org.gradle.api.GradleException(
                    "INV-AC-2170: ${violations.size} error code(s) renumbered or repurposed since the previous train:\n" +
                        violations.joinToString("\n") { "  - ${it.code}: ${it.reason}" },
                )
            }
        }
    }

val checkBlockDispositionCauses =
    tasks.register("checkBlockDispositionCauses") {
        group = "verification"
        description = "INV-AC-2173: every BLOCK code has the disposition its cause calls for."
        inputs.file(sourceYaml)
        val reportFile = layout.buildDirectory.file("reports/catalogue/block-disposition.txt")
        outputs.file(reportFile)
        doLast {
            val rows = ErrorCodeYamlParser.parse(sourceYaml)
            val result = BlockDispositionChecker.check(rows)
            val report =
                buildString {
                    appendLine("INV-AC-2173 — BLOCK disposition-follows-cause check")
                    appendLine()
                    appendLine("Classified and verified (${result.classified.size}):")
                    result.classified.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Listed for decision — cause not yet classified in live Keel text (${result.pendingDecision.size}):")
                    result.pendingDecision.forEach { appendLine("  - $it") }
                    if (result.violations.isNotEmpty()) {
                        appendLine()
                        appendLine("Violations (${result.violations.size}):")
                        result.violations.forEach { appendLine("  - $it") }
                    }
                }
            val out = reportFile.get().asFile
            out.parentFile.mkdirs()
            out.writeText(report)
            logger.lifecycle(report)
            if (result.violations.isNotEmpty()) {
                throw org.gradle.api.GradleException(
                    "INV-AC-2173: ${result.violations.size} BLOCK code(s) do not have the disposition their cause calls for; " +
                        "see ${out.relativeTo(rootProject.projectDir)}.",
                )
            }
        }
    }

sourceSets {
    main {
        kotlin.srcDir(generateErrorCodeCatalogue.map { generatedKotlinDir.get() })
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateErrorCodeCatalogue)
}

tasks.named("check") {
    dependsOn(checkCatalogueStability, checkBlockDispositionCauses)
}

// Generated code is not hand style-reviewed (same rule as gst.contract-server in
// platform-build): it only has to compile under the pinned toolchain.
tasks.matching { it.name == "detekt" || it.name == "detektMain" }.configureEach {
    (this as? org.gradle.api.tasks.util.PatternFilterable)?.exclude("**/generated/**")
}
