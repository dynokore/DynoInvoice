package com.dynokore.gst.controlPlane.acceptance.architecture

import com.dynokore.gst.controlPlane.acceptance.gates.SourceText
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

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
        val root =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        val pkg = SERVICE_PACKAGE
        write(root, "control-plane-adapters/src/main/kotlin/x/stage/Stage.kt", stageFile(pkg))
        StagingWriterRule.findings(root) shouldBe emptyList<String>()
        write(root, "control-plane-adapters/src/main/kotlin/x/http/Bad.kt", badFile(pkg))
        StagingWriterRule.findings(root).size shouldBe 1
        // the simple name in a comment or a string is not a use; a fully qualified use without an import is
        write(root, "control-plane-bootstrap/src/main/kotlin/y/Ok.kt", okFile(pkg))
        StagingWriterRule.findings(root).size shouldBe 1
        write(root, "control-plane-bootstrap/src/main/kotlin/y/Fq.kt", fullyQualifiedFile(pkg))
        StagingWriterRule.findings(root).size shouldBe 2
        // a look-alike package does not count as the stage package
        write(root, "control-plane-adapters/src/main/kotlin/z/Look.kt", lookAlikeFile(pkg))
        StagingWriterRule.findings(root).size shouldBe 3
    }

    private fun stageFile(pkg: String) =
        "package $pkg.adapters.stage\nclass StagingRecordWriter\nclass S(val w: StagingRecordWriter)\n"

    private fun badFile(pkg: String) =
        "package $pkg.adapters.http\nimport $pkg.adapters.stage.StagingRecordWriter\n" +
            "class Bad(val w: StagingRecordWriter)\n"

    private fun okFile(pkg: String) =
        "package $pkg.bootstrap\n// StagingRecordWriter\nval s = \"StagingRecordWriter\"\n"

    private fun fullyQualifiedFile(pkg: String) =
        "package $pkg.bootstrap\nval w = $pkg.adapters.stage.StagingRecordWriter::class\n"

    private fun lookAlikeFile(pkg: String) = "package $pkg.adapters.stagehand\nclass Look(val w: StagingRecordWriter)\n"

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
                val code = SourceText.stripCommentsAndStrings(file.readText())
                val pkg =
                    PACKAGE
                        .find(code)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                val inStage = pkg == STAGE || pkg.startsWith("$STAGE.")
                !inStage && WORD.containsMatchIn(code)
            }.map { it.path }
            .sorted()
            .toList()
}
