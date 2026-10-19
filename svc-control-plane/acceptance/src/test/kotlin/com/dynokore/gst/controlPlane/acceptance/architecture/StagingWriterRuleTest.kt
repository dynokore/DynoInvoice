package com.dynokore.gst.controlPlane.acceptance.architecture

import org.junit.jupiter.api.Test
import java.io.File
import io.kotest.matchers.shouldBe

/**
 * The staging evidence write path (coordinator ruling INV-Q-4878): `StagingRecordWriter` is used only by the stage of
 * INV-T-4985, the package `adapters.stage`. A class outside it that names the writer fails `check`. Deny by default:
 * any production Kotlin file outside the stage package whose code (comments removed) holds the word is a finding.
 */
class StagingWriterRuleTest {
    @Test
    fun `no production class outside the stage package names StagingRecordWriter`() {
        val findings = StagingWriterRule.findings(Repository.root)
        findings shouldBe emptyList<String>()
    }

    @Test
    fun `a call from a class outside the stage package is a finding`() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        write(root, "control-plane-adapters/src/main/kotlin/x/stage/Stage.kt", "package $SERVICE_PACKAGE.adapters.stage\nclass StagingRecordWriter\nclass Stage(val w: StagingRecordWriter)\n")
        StagingWriterRule.findings(root) shouldBe emptyList<String>()
        write(root, "control-plane-adapters/src/main/kotlin/x/http/Bad.kt", "package $SERVICE_PACKAGE.adapters.http\nimport $SERVICE_PACKAGE.adapters.stage.StagingRecordWriter\nclass Bad(val w: StagingRecordWriter)\n")
        StagingWriterRule.findings(root).size shouldBe 1
        // the same simple name in a string or comment is not a use; a fully qualified use without an import is
        write(root, "control-plane-bootstrap/src/main/kotlin/y/Ok.kt", "package $SERVICE_PACKAGE.bootstrap\n// StagingRecordWriter\nval s = \"StagingRecordWriter\"\n")
        StagingWriterRule.findings(root).size shouldBe 1
        write(root, "control-plane-bootstrap/src/main/kotlin/y/Fq.kt", "package $SERVICE_PACKAGE.bootstrap\nval w = $SERVICE_PACKAGE.adapters.stage.StagingRecordWriter::class\n")
        StagingWriterRule.findings(root).size shouldBe 2
        // a look-alike package does not count as the stage package
        write(root, "control-plane-adapters/src/main/kotlin/z/Look.kt", "package $SERVICE_PACKAGE.adapters.stagehand\nclass Look(val w: StagingRecordWriter)\n")
        StagingWriterRule.findings(root).size shouldBe 3
    }

    private fun write(
        root: File,
        path: String,
        text: String,
    ) = File(root, path).also { it.parentFile.mkdirs() }.writeText(text)
}

internal object StagingWriterRule {
    private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)""")
    private val WORD = Regex("""\bStagingRecordWriter\b""")
    private const val STAGE = "$SERVICE_PACKAGE.adapters.stage"

    fun findings(root: File): List<String> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .filter { file ->
                val code = com.dynokore.gst.controlPlane.acceptance.gates.stripForRule(file.readText())
                val pkg = PACKAGE.find(code)?.groupValues?.get(1).orEmpty()
                val inStage = pkg == STAGE || pkg.startsWith("$STAGE.")
                !inStage && WORD.containsMatchIn(code)
            }.map { it.path }
            .sorted()
            .toList()
}
