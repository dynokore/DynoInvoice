package com.dynokore.gst.rules.bootstrap

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The layer rule of the dry run (INV-T-4974, owner ruling INV-Q-4870 B): the classes of `rules-domain` and the engine
 * classes of `rules-application` (`RunDryRun`, `DryRunPorts`, `PackDocumentEvaluation`) know the ports and nothing of
 * an adapter or an event. Deny by default (P140): an import outside the small allowlist is a finding, and so is a
 * use of
 * an event type that needs no import because it lives in the same package.
 */
class DryRunPortLayerTest {
    private val root = File("..").canonicalFile

    private fun sources(dir: File): List<File> = dir.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun domainFiles(base: File) = sources(File(base, "rules-domain/src/main"))

    private fun engineFiles(base: File) =
        sources(File(base, "rules-application/src/main")).filter { it.nameWithoutExtension in ENGINE }

    /** The findings of the domain and engine files below [base]; each names the file and the offending line. */
    private fun findings(base: File): List<String> =
        (domainFiles(base) + engineFiles(base)).flatMap { file ->
            val text = stripComments(file.readText())
            val imports =
                text
                    .lines()
                    .map { it.trim() }
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").trim() }
                    .filterNot { import -> ALLOWED.any { import == it.removeSuffix(".") || import.startsWith(it) } }
                    .map { "${file.name}: import $it" }
            val events = EVENT_TYPE.findAll(text).map { "${file.name}: uses ${it.value}" }.toList()
            imports + events
        }

    private fun stripComments(source: String): String =
        source.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("//[^\n]*"), "")

    @Test
    fun `the domain and the engine of the dry run import no adapter and no event class`() {
        val found = findings(root)
        check(found.isEmpty()) { "INV-Q-4870: layer breaches $found" }
        check(domainFiles(root).isNotEmpty() && engineFiles(root).size == ENGINE.size) {
            "the scan found ${engineFiles(root).map { it.name }}, expected $ENGINE: a renamed class escapes the rule"
        }
    }

    private fun planted(
        dir: File,
        module: String,
        file: String,
        body: String,
    ) {
        val target = File(dir, "$module/src/main/kotlin/com/dynokore/gst/rules/$file")
        target.parentFile.mkdirs()
        target.writeText("package x\n$body\nclass Planted\n")
    }

    @Test
    fun `probe - an adapter import in a domain class is a finding`(
        @TempDir dir: File,
    ) {
        planted(dir, "rules-domain", "domain/Planted.kt", "import com.dynokore.gst.rules.adapters.web.PacksController")
        check(findings(dir).any { it.contains("adapters.web.PacksController") })
    }

    @Test
    fun `probe - an adapter import in an engine class is a finding`(
        @TempDir dir: File,
    ) {
        planted(
            dir,
            "rules-application",
            "application/RunDryRun.kt",
            "import com.dynokore.gst.rules.adapters.events.DryRunSampleEvents",
        )
        check(findings(dir).any { it.contains("DryRunSampleEvents") })
    }

    @Test
    fun `probe - an event class import or use in an engine class is a finding`(
        @TempDir dir: File,
    ) {
        planted(
            dir,
            "rules-application",
            "application/DryRunPorts.kt",
            "import com.dynokore.platform.outboxandqueue.OutboxWriter",
        )
        planted(dir, "rules-application", "application/PackDocumentEvaluation.kt", "val e: PackEventEmitter? = null")
        val found = findings(dir)
        check(found.any { it.contains("OutboxWriter") } && found.any { it.contains("PackEventEmitter") }) { "$found" }
    }

    @Test
    fun `look-alike - the same imports in rules-adapters are no finding`(
        @TempDir dir: File,
    ) {
        planted(
            dir,
            "rules-adapters",
            "adapters/RunDryRun.kt",
            "import com.dynokore.gst.rules.adapters.web.PacksController",
        )
        check(findings(dir).isEmpty())
    }

    @Test
    fun `look-alike - a comment or a string naming an adapter is no finding`(
        @TempDir dir: File,
    ) {
        planted(
            dir,
            "rules-domain",
            "domain/Planted.kt",
            "// import com.dynokore.gst.rules.adapters.web.X\n/* PackEventEmitter */",
        )
        check(findings(dir).isEmpty())
    }

    private companion object {
        val ENGINE = setOf("RunDryRun", "DryRunPorts", "PackDocumentEvaluation")
        val ALLOWED =
            listOf(
                "com.dynokore.gst.rules.domain.",
                "com.dynokore.gst.rules.application.",
                "com.dynokore.gst.catalogue.", // the error catalogue: a verdict code and its severity
                "com.dynokore.platform.tenancykernel.",
                "com.dynokore.platform.rulepackkernel.",
                "java.",
                "kotlin.",
            )
        val EVENT_TYPE = Regex("\\b(PackEvent\\w*|Outbox\\w*|EventContext|EventCatalogue|InboxConsumer)\\b")
    }
}
