package com.dynokore.gst.evidence.domain.chain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ChainTypesTest {
    @Test
    fun `the record kinds are exactly the four tables`() {
        RecordKind.entries.map { it.name to it.table } shouldBe
            listOf(
                "DECISION_TRACE" to "decision_trace",
                "EVIDENCE_PAYLOAD" to "evidence_payload",
                "ACKNOWLEDGEMENT" to "acknowledgement",
                "AUDIT_LOG" to "audit_log",
            )
    }

    @Test
    fun `a chain key refuses a blank entity or period`() {
        shouldThrow<IllegalArgumentException> { ChainKey(" ", "2026-Q1") }
        shouldThrow<IllegalArgumentException> { ChainKey("E", "") }
    }

    @Test
    fun `a locator names the chain of its entity and period`() {
        val locator = DocumentLocator(UUID.randomUUID(), 1, "E", "2026-Q1", LocalDate.of(2026, 1, 2), "t", null)
        locator.chain shouldBe ChainKey("E", "2026-Q1")
    }
}
