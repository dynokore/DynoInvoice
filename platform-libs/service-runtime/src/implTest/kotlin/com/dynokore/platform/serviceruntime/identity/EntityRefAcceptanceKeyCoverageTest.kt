package com.dynokore.platform.serviceruntime.identity

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A key of INV-T-8522's `ac_refs` with no test naming it as a whole token fails the build (as AcceptanceKeyCoverageTest
 * does for INV-T-5180): a key inside a longer number or identifier does not count.
 */
class EntityRefAcceptanceKeyCoverageTest {
    private val keys = listOf("INV-AC-8515")

    private fun namesTest(
        key: String,
        source: String,
    ): Boolean {
        val whole = Regex("""(?<![A-Za-z0-9-])${Regex.escape(key)}(?![A-Za-z0-9])""")
        return source.lines().any { it.trimStart().startsWith("fun `") && whole.containsMatchIn(it) }
    }

    private fun missing(sources: List<String>) = keys.filter { key -> sources.none { namesTest(key, it) } }

    @Test
    fun `every ac_refs key of INV-T-8522 is named by a test`() {
        val sources =
            Files.walk(Path.of("src/implTest/kotlin")).use { paths ->
                paths.filter { it.toString().endsWith(".kt") }.map { Files.readString(it) }.toList()
            }
        missing(sources).shouldBeEmpty()
    }

    @Test
    fun `the check fails when a test is deleted and when only a look-alike key names it`() {
        missing(listOf("    fun `INV-AC-8515 a`() {}")) shouldBe emptyList()
        missing(listOf("    fun `other`() {}")) shouldBe keys
        missing(listOf("    fun `INV-AC-85150 a`() {}")) shouldBe keys
        missing(listOf("    fun `INV-AC-8515x a`() {}")) shouldBe keys
        missing(listOf("// INV-AC-8515")) shouldBe keys
    }
}
