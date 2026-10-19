package com.dynokore.gst.rules.bootstrap.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The probes of the acKeyCoverage gate, run on planted tables and sources (the repository's own files are untouched).
 */
class AcKeyCoverageTest {
    private val table = "criterion\ttask\twave\nINV-AC-100\tINV-T-1\t2\nINV-AC-200\tINV-T-1\t3\n"

    private fun source(vararg names: String): Map<String, String> =
        mapOf("m/src/test/kotlin/T.kt" to names.joinToString("\n") { "    @Test\n    fun `$it`() {}\n" })

    @Test
    fun `a covered table passes, and a deferred row needs no test`() {
        AcKeyCoverage.check(table, source("INV-AC-100 resolves")).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a wave 2 row fails and names the row`() {
        val findings = AcKeyCoverage.check(table, source("something else"))
        findings shouldHaveSize 1
        findings.single() shouldContain "INV-AC-100"
    }

    @Test
    fun `renaming a test to a longer look-alike key fails twice - the row has no test and the new key is in no row`() {
        val findings = AcKeyCoverage.check(table, source("INV-AC-1000 resolves"))
        findings shouldHaveSize 2
        findings.joinToString() shouldContain "INV-AC-100 (task INV-T-1, wave 2)"
        findings.joinToString() shouldContain "INV-AC-1000"
    }

    @Test
    fun `other look-alikes never count as the key`() {
        listOf("XINV-AC-100", "INV-AC-100-2", "INV-AC-100x", "inv-ac-100").forEach { name ->
            AcKeyCoverage.check(table, source(name)).joinToString() shouldContain "INV-AC-100 (task INV-T-1, wave 2)"
        }
        AcKeyCoverage.check(table, source("(INV-AC-100), resolves")).shouldBeEmpty()
    }

    @Test
    fun `an empty table, a missing table and a header-only table fail`() {
        AcKeyCoverage.check("", source("INV-AC-100")).first() shouldContain "header"
        AcKeyCoverage.check(null, source("INV-AC-100")).first() shouldContain "missing"
        AcKeyCoverage.check("criterion\ttask\twave\n", source("x")).single() shouldContain "no rows"
    }

    @Test
    fun `malformed and duplicate rows fail`() {
        AcKeyCoverage
            .check(
                "criterion\ttask\twave\nINV-AC-100\tINV-T-1\t5\n",
                source("INV-AC-100"),
            ).joinToString() shouldContain
            "wave"
        AcKeyCoverage
            .check(
                "criterion\ttask\twave\nINV-AC-100\tINV-T-1\t2\nINV-AC-100\tINV-T-2\t2\n",
                source("INV-AC-100"),
            ).joinToString() shouldContain
            "more than once"
        AcKeyCoverage.check("criterion\ttask\twave\nAC-100\tINV-T-1\t2\n", source("x")).joinToString() shouldContain
            "not a criterion key"
    }

    @Test
    fun `a key in a comment, a string, a helper, a disabled test or a class name is not a test`() {
        val hidden =
            mapOf(
                "m/src/test/kotlin/T.kt" to
                    """
                    // @Test fun `INV-AC-100 in a line comment`() {}
                    /* /* nested */ @Test fun `INV-AC-100 in a block comment`() {} */
                    val s = "@Test fun `INV-AC-100 in a string`() {}"
                    fun `INV-AC-100 helper without the annotation`() {}
                    @Test @Disabled fun `INV-AC-100 disabled`() {}
                    class INV-AC-100
                    """.trimIndent(),
            )
        AcKeyCoverage.check(table, hidden).joinToString() shouldContain "INV-AC-100 (task INV-T-1, wave 2)"
    }

    @Test
    fun `an unknown key in a test name fails even when every row is covered`() {
        val findings = AcKeyCoverage.check(table, source("INV-AC-100 ok", "INV-AC-999 not in the table"))
        findings.single() shouldContain "INV-AC-999"
    }
}
