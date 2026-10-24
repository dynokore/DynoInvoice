package com.dynokore.gst.notification.application

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-Q-9741 A: the service holds no list of event names. No string literal of the main sources of the four layers
 * equals one of the five names of INV-REQ-820 (compared by equality after removing the quotes); the names appear in
 * tests as data. Fails closed: a scanned module with no Kotlin file, or no module directory, fails.
 */
class EventNameSourceScanTest {
    private val root = File(System.getProperty("gst.root.dir") ?: "..").absoluteFile.normalize()
    private val modules =
        listOf("notification-domain", "notification-application", "notification-adapters", "notification-bootstrap")

    private fun sources(module: String): List<File> {
        val main = File(root, "$module/src/main")
        check(main.isDirectory) { "$main is missing" }
        return main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no main source of the four layers holds one of the five event names as a string literal`() {
        val findings =
            modules.flatMap { module ->
                sources(module).flatMap { file ->
                    literalsOf(file.readText()).filter { it in NAMES }.map { "${file.relativeTo(root)}: $it" }
                }
            }
        findings.shouldBeEmpty()
    }

    @Test
    fun `every scanned module has Kotlin sources`() {
        modules.forEach { check(sources(it).isNotEmpty()) { "$it has no Kotlin source" } }
    }

    @Test
    fun `probe - the scan finds the name in a const val, in a raw string and in a one-line list, not a look-alike`() {
        literalsOf("""const val HELD = "document.held"""") shouldBe listOf("document.held")
        literalsOf("val a = \"\"\"document.blocked\"\"\"") shouldBe listOf("document.blocked")
        literalsOf("""val l = listOf("x", "period.readiness_changed"); val m = "document.rejected"""") shouldBe
            listOf("x", "period.readiness_changed", "document.rejected")
        (literalsOf("""const val H = "document.held.v2"""").any { it in NAMES }) shouldBe false
        (literalsOf("""const val H = "document.held"""").any { it in NAMES }) shouldBe true
    }

    /** The content of every string literal (plain or raw, comments skipped is not needed: a comment is no literal). */
    private fun literalsOf(text: String): List<String> =
        Regex("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toList()

    private companion object {
        val NAMES =
            setOf(
                "document.acknowledged",
                "document.rejected",
                "document.held",
                "document.blocked",
                "period.readiness_changed",
            )
    }
}
