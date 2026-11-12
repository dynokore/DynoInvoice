package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.queue.CatalogueEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RaisedCodeTest {
    private val entry = CatalogueEntry("SUB-010", "Tax operations", "MANUAL")

    @Test
    fun `INV-AC-21086 members come from the entry`() {
        val r = RaisedCode.of("D1", 1, "E-001", "2026-Q1", "SUB-010", entry)
        r.remediationOwner shouldBe "Tax operations"
        r.disposition shouldBe "MANUAL"
    }

    @Test
    fun `INV-AC-21086 members are null without an entry`() {
        val r = RaisedCode.of("D1", 1, "E-001", "2026-Q1", "X-999", null)
        r.remediationOwner shouldBe null
        r.disposition shouldBe null
    }

    @Test
    fun `INV-AC-21086 blank members and version below 1 are refused`() {
        shouldThrow<IllegalArgumentException> { RaisedCode.of("D1", 1, " ", "2026-Q1", "C", null) }
        shouldThrow<IllegalArgumentException> { RaisedCode.of("D1", 1, "E", "", "C", null) }
        shouldThrow<IllegalArgumentException> { RaisedCode.of("D1", 1, "E", "P", "", null) }
        shouldThrow<IllegalArgumentException> { RaisedCode.of("D1", 0, "E", "P", "C", null) }
    }
}
