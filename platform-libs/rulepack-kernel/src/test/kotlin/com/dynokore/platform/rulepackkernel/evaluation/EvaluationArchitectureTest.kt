package com.dynokore.platform.rulepackkernel.evaluation

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Purity rule of the evaluation package (INV-REQ-775, INV-AC-9791), deny-by-default: a file of the package may import
 * only the exact prefixes below, so `java.time.Clock`, `java.net`, `org.springframework` and `com.dynokore.gst.rules`
 * are findings, as is anything else unknown. Two further checks close the gaps an import list leaves: no fully
 * qualified name outside the allowlist in code, and no use of the implicitly imported `java.lang` clock, thread and
 * process types (`System`, `Thread`, `Runtime`, `ProcessBuilder`). Comments and string literals are stripped first.
 */
class EvaluationArchitectureTest {
    private val ownPackage = "com.dynokore.platform.rulepackkernel.evaluation"
    private val allowedImports =
        listOf(
            "$ownPackage.",
            "com.dynokore.platform.rulepackkernel.PackId",
            "com.dynokore.platform.rulepackkernel.PackType",
            "com.dynokore.platform.rulepackkernel.PackVersionLabel",
            "com.dynokore.platform.rulepackkernel.trace.",
            "com.dynokore.platform.rulepackkernel.dryrun.",
            "java.math.BigDecimal",
            "kotlin.collections.",
        )
    private val implicitJavaLang = Regex("""\b(System|Thread|Runtime|ProcessBuilder|Math\.random)\b""")
    private val knownRoots = "java|javax|jakarta|kotlin|kotlinx|org|com|io|net"
    private val qualifiedName =
        Regex("""\b(?:(?:$knownRoots)\.(?:[a-z][a-z0-9]*\.)+[A-Za-z]|(?:[a-z][a-z0-9]*\.){2,}[A-Z])""")

    private fun code(text: String): String =
        text
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"")
            .lines()
            .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
            .joinToString("\n")

    private fun assertPure(scope: KoScope) {
        val files = scope.files.filter { "/src/test/" !in it.path }
        files.assertTrue { f -> f.imports.all { i -> allowedImports.any { i.name.startsWith(it) } } }
        files.assertTrue { f ->
            val body = code(File(f.path).readText())
            !implicitJavaLang.containsMatchIn(body) &&
                qualifiedName.findAll(body).all { m ->
                    allowedImports.any { m.value.dropLast(1).startsWith(it.trimEnd('.')) }
                }
        }
    }

    @Test
    fun `the evaluation package imports nothing outside the allowlist`() {
        val scope = Konsist.scopeFromModule("rulepack-kernel", "main")
        val own = scope.files.filter { "/rulepackkernel/evaluation/" in it.path && "/src/test/" !in it.path }
        check(own.isNotEmpty()) { "no file of the evaluation package found" }
        assertPure(Konsist.scopeFromExternalDirectory(File(own.first().path).parentFile.path))
    }

    @Test
    fun `probe - the rule fails on a clock, network, Spring, rules-svc, look-alike or implicit java lang use`(
        @TempDir dir: File,
    ) {
        val offenders =
            listOf(
                "import java.time.Clock\nclass O",
                "import java.net.URI\nclass O",
                "import org.springframework.stereotype.Component\nclass O",
                "import com.dynokore.gst.rules.domain.resolution.PackBinding\nclass O",
                "import com.dynokore.platform.rulepackkernel.evaluationx.Stub\nclass O",
                "import com.dynokore.platform.rulepackkernel.PackRegistry\nclass O",
                "class O { val t = System.currentTimeMillis() }",
                "class O { val t = java.time.Instant.now() }",
                "class O { val t = kotlin.random.Random.nextInt() }",
            )
        offenders.forEachIndexed { i, body ->
            val only = File(dir, "only$i").also { it.mkdirs() }
            File(only, "Offender$i.kt").writeText("package $ownPackage\n$body\n")
            assertThrows(
                Throwable::class.java,
                { assertPure(Konsist.scopeFromExternalDirectory(only.absolutePath)) },
                body,
            )
        }
    }
}
