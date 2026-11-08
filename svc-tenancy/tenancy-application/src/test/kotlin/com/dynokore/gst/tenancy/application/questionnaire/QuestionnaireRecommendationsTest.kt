package com.dynokore.gst.tenancy.application.questionnaire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class QuestionnaireRecommendationsTest {
    @ParameterizedTest
    @MethodSource("rows")
    fun `the ordered ref list of each question key equals the table of section 37_5`(
        key: String,
        expected: List<String>,
    ) {
        val entries = QuestionnaireRecommendations.entries("2026.1", mapOf(key to "a"))
        assertEquals(expected, entries.map { it.ref })
        assertEquals(expected.size, entries.map { it.ref }.toSet().size, "no ref twice for $key")
        assertTrue(entries.all { it.reason == "$key=a" })
        assertTrue(entries.all { (it.kind == Recommendation.Kind.PROFILE) == it.ref.startsWith("PRF-") })
    }

    @Test
    fun `INV-AC-2232 an agent-filed client gives a PROFILE entry PRF-5 with question and answer as reason`() {
        val entries = QuestionnaireRecommendations.entries("2026.1", mapOf("OQ-2" to "an agent files the return"))
        val prf5 = entries.single { it.ref == "PRF-5" }
        assertEquals(Recommendation.Kind.PROFILE, prf5.kind)
        assertEquals("OQ-2=an agent files the return", prf5.reason)
    }

    @Test
    fun `INV-AC-2387 a GST group gives member variation points and no PRF-3 entry`() {
        val entries = QuestionnaireRecommendations.entries("2026.1", mapOf("OQ-5" to "a GST group", "OQ-1" to "more"))
        assertFalse(entries.any { it.ref == "PRF-3" })
        listOf("VP-01", "VP-02", "VP-15", "VP-28", "VP-33").forEach { ref ->
            assertTrue(entries.any { it.ref == ref && it.reason == "OQ-5=a GST group" }, ref)
        }
        assertTrue(entries.any { it.ref == "VP-01" && it.reason == "OQ-1=more" })
    }

    @Test
    fun `the keys are in numeric order and the look-alike key OQ-15 and version 2026_2 give no entry`() {
        val ordered = QuestionnaireRecommendations.entries("2026.1", mapOf("OQ-10" to "x", "OQ-2" to "y"))
        assertEquals(listOf("VP-03", "PRF-5", "PRF-6", "VP-10", "VP-11", "VP-53"), ordered.map { it.ref })
        assertTrue(QuestionnaireRecommendations.entries("2026.1", mapOf("OQ-15" to "x")).isEmpty())
        assertTrue(QuestionnaireRecommendations.entries("2026.2", mapOf("OQ-1" to "x")).isEmpty())
    }

    companion object {
        @JvmStatic
        fun rows(): Stream<Arguments> =
            Stream.of(
                row("OQ-1", "PRF-1", "PRF-2", "VP-01", "VP-02", "VP-28", "VP-33", "VP-15", "VP-03", "VP-40"),
                row("OQ-2", "VP-03", "PRF-5", "PRF-6", "VP-10", "VP-11"),
                row("OQ-3", "VP-11", "VP-14", "VP-32", "VP-15", "VP-46"),
                row("OQ-4", "VP-01", "VP-02"),
                row("OQ-5", "VP-28", "VP-33", "VP-51", "VP-64", "VP-01", "VP-02", "VP-15"),
                row("OQ-6", "VP-25", "VP-26", "VP-49"),
                row("OQ-7", "VP-58"),
                row("OQ-8", "VP-24", "VP-37"),
                row("OQ-9", "VP-43", "VP-44", "VP-52", "VP-01", "VP-02", "VP-05", "VP-08", "VP-32"),
                row("OQ-10", "VP-53"),
                row("OQ-11", "VP-54"),
                row("OQ-12", "VP-48", "VP-49", "VP-50", "VP-71"),
                row("OQ-13", "VP-51"),
                row("OQ-14", "VP-22", "VP-23"),
            )

        private fun row(
            key: String,
            vararg refs: String,
        ) = Arguments.of(key, refs.toList())
    }
}
