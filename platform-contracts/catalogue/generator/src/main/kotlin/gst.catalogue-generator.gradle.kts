import gst.catalogue.BlockDispositionChecker
import gst.catalogue.CatalogueArtefact
import gst.catalogue.ErrorCodeYamlParser
import gst.catalogue.KotlinEmitter
import gst.catalogue.MustBeElectedAvailabilityChecker
import gst.catalogue.NonWaivableFloorChecker
import gst.catalogue.StabilityChecker
import gst.catalogue.VariationPointCatalogueArtefact
import gst.catalogue.VariationPointKotlinEmitter
import gst.catalogue.VariationPointYamlParser

// gst.catalogue-generator.gradle.kts — INV-T-2851 (T7) / INV-T-2852 (T8), STK-61,
// CS-13, CS-28.
//
// Applied to the `gst-catalogue` module only (a single consumer, unlike build-logic's
// gst.* plugins). Two independent generations, sharing this one plugin and its
// `check` wiring (STK-61's "one catalogue" for both `ErrorCode` and `VariationPoint`):
// - reads catalogue/errors/error-codes.yaml (vendored, pinned-commit copy of
//   invoice-docs' model/architecture/error-codes.yaml) and generates ErrorCode.kt
//   plus its catalogue artefact JSON, then runs INV-AC-2170/INV-AC-2173 (T7);
// - reads catalogue/variation-points/variation-points.yaml (vendored from the Keel
//   INV variation-point requirements — see that file's own header for why there is
//   no invoice-docs file to vendor from instead) and generates VariationPoint.kt plus
//   its catalogue artefact JSON, then runs INV-AC-2236/INV-AC-1786 (T8).

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

// --- VariationPoint (INV-T-2852 / T8) ---------------------------------------------

val vpSourceYaml = rootProject.layout.projectDirectory.file("variation-points/variation-points.yaml").asFile
val vpGeneratedKotlinDir = layout.buildDirectory.dir("generated/gst-catalogue-vp/kotlin")
// Same "generated/gst-catalogue/" directory the ErrorCode artefact already writes to
// (not a separate "-vp" one): ci/.gitlab-ci.yml's catalogue-publish job already
// collects that whole directory as a build artifact, so the VariationPoint artefact
// rides along without a second `artifacts.paths` entry — only its own `curl` publish
// step is new.
val vpArtefactFile =
    layout.buildDirectory.file("generated/gst-catalogue/gst-catalogue-variation-points-$catalogueVersion.json")

val generateVariationPointCatalogue =
    tasks.register("generateVariationPointCatalogue") {
        group = "catalogue"
        description = "STK-61/CS-28: generates VariationPoint.kt and the catalogue artefact JSON from variation-points.yaml."
        inputs.file(vpSourceYaml)
        outputs.dir(vpGeneratedKotlinDir)
        outputs.file(vpArtefactFile)
        doLast {
            val rows = VariationPointYamlParser.parse(vpSourceYaml)
            val outDir = vpGeneratedKotlinDir.get().asFile.resolve("com/dynokore/gst/catalogue")
            outDir.mkdirs()
            outDir.resolve("VariationPoint.kt").writeText(
                VariationPointKotlinEmitter.emit("com.dynokore.gst.catalogue", rows),
            )
            val artefact = vpArtefactFile.get().asFile
            artefact.parentFile.mkdirs()
            artefact.writeText(VariationPointCatalogueArtefact.render(rows, catalogueVersion))
        }
    }

val checkMustBeElectedAvailability =
    tasks.register("checkMustBeElectedAvailability") {
        group = "verification"
        description = "INV-AC-2236: every must-be-elected VP, and every one closing a named open question, is available."
        inputs.file(vpSourceYaml)
        doLast {
            val rows = VariationPointYamlParser.parse(vpSourceYaml)
            val violations = MustBeElectedAvailabilityChecker.check(rows)
            if (violations.isNotEmpty()) {
                throw org.gradle.api.GradleException(
                    "INV-AC-2236: ${violations.size} variation point(s) must be available but are not:\n" +
                        violations.joinToString("\n") { "  - ${it.id}: ${it.reason}" },
                )
            }
        }
    }

val checkNonWaivableFloor =
    tasks.register("checkNonWaivableFloor") {
        group = "verification"
        description = "INV-AC-1786 (checkable half): a non-waivable CONTROL has no looser option; any other CONTROL's " +
            "looser option needs a recorded acknowledgement."
        inputs.file(vpSourceYaml)
        val reportFile = layout.buildDirectory.file("reports/catalogue/non-waivable-floor.txt")
        outputs.file(reportFile)
        doLast {
            val rows = VariationPointYamlParser.parse(vpSourceYaml)
            val result = NonWaivableFloorChecker.check(rows)
            val report =
                buildString {
                    appendLine("INV-AC-1786 — non-waivable-floor check (checkable half only; see NonWaivableFloorChecker's own KDoc)")
                    appendLine()
                    appendLine("Classified and verified (${result.classified.size}):")
                    result.classified.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Listed for decision (${result.pendingDecision.size}):")
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
                    "INV-AC-1786: ${result.violations.size} CONTROL variation point(s) offer a looser option with no " +
                        "recorded-acknowledgement marker; see ${out.relativeTo(rootProject.projectDir)}.",
                )
            }
        }
    }

sourceSets {
    main {
        kotlin.srcDir(generateErrorCodeCatalogue.map { generatedKotlinDir.get() })
        kotlin.srcDir(generateVariationPointCatalogue.map { vpGeneratedKotlinDir.get() })
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateErrorCodeCatalogue, generateVariationPointCatalogue)
}

tasks.named("check") {
    dependsOn(checkCatalogueStability, checkBlockDispositionCauses, checkMustBeElectedAvailability, checkNonWaivableFloor)
}

// Generated code is not hand style-reviewed (same rule as gst.contract-server in
// platform-build): it only has to compile under the pinned toolchain.
tasks.matching { it.name == "detekt" || it.name == "detektMain" }.configureEach {
    (this as? org.gradle.api.tasks.util.PatternFilterable)?.exclude("**/generated/**")
}
