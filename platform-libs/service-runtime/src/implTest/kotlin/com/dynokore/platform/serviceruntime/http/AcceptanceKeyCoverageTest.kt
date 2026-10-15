package com.dynokore.platform.serviceruntime.http

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A key of INV-T-5180's `ac_refs` with no test naming it as a whole token fails the build. A test names a key in its
 * function name; a key inside a longer number or identifier (INV-AC-22240 for INV-AC-2224) does not count.
 */
class AcceptanceKeyCoverageTest {
    private val keys = listOf("INV-AC-2224", "INV-AC-2227", "INV-AC-2229", "INV-AC-2230", "INV-AC-205")

    private fun namesTest(
        key: String,
        source: String,
    ): Boolean {
        val whole = Regex("""(?<![A-Za-z0-9-])${Regex.escape(key)}(?![A-Za-z0-9])""")
        return source.lines().any { it.trimStart().startsWith("fun `") && whole.containsMatchIn(it) }
    }

    private fun missing(sources: List<String>) = keys.filter { key -> sources.none { namesTest(key, it) } }

    @Test
    fun `every ac_refs key of the task is named by a test`() {
        val root = Path.of("src/implTest/kotlin")
        val sources =
            Files.walk(root).use { paths ->
                paths.filter { it.toString().endsWith(".kt") }.map { Files.readString(it) }.toList()
            }
        missing(sources).shouldBeEmpty()
    }

    @Test
    fun `the check fails when a key has no test and when only a look-alike names it`() {
        val all = keys.joinToString("\n") { "    fun `$it does the thing`() {}" }
        missing(listOf(all)) shouldBe emptyList()
        // a deleted test
        missing(listOf(all.lines().filterNot { "INV-AC-2227" in it }.joinToString("\n"))) shouldBe listOf("INV-AC-2227")
        // a renamed one: the key inside a longer number, and a prefix of another key
        val lookAlike = all.replace("INV-AC-2224 ", "INV-AC-22240 ").replace("INV-AC-205 ", "INV-AC-2050 ")
        missing(listOf(lookAlike)) shouldBe listOf("INV-AC-2224", "INV-AC-205")
        // a mention outside a test name is no test
        missing(listOf("// INV-AC-2224 INV-AC-2227 INV-AC-2229 INV-AC-2230 INV-AC-205")) shouldBe keys
    }
}
