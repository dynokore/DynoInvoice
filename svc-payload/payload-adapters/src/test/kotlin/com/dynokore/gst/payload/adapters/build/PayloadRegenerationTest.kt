package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.Projection
import com.dynokore.gst.payload.domain.regeneration.CorpusCoverage
import com.dynokore.gst.payload.domain.regeneration.CorpusDocumentFunction
import com.dynokore.gst.payload.domain.regeneration.CorpusEntry
import com.dynokore.gst.payload.domain.regeneration.CoverageGap
import com.dynokore.gst.payload.domain.regeneration.RegenerationCorpusCoverage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Golden-master: the stored straddling credit note rebuilds to its archived bytes (INV-REQ-591, INV-REQ-704). */
class PayloadRegenerationTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/regeneration/$name")) { name }.use { it.readBytes() }

    private val stored = String(resource("credit-note-381-straddling.canonical.json"), Charsets.UTF_8)
    private val archived = resource("credit-note-381-straddling.archived.xml")

    private val projector =
        PintSgPayloadProjector({ "PINT_SG" }, { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), "{}") })

    private fun build(document: String = stored): ByteArray {
        val projection = projector.project(BuildInput("E-001", document, null, "SALE", true), "TYPE_2")
        return (projection as Projection.Built).bytes
    }

    private fun entry(preceding: String) =
        CorpusEntry(
            "CN-2024-000045",
            CorpusDocumentFunction.CREDIT_NOTE,
            LocalDate.parse("2024-02-01"),
            LocalDate.parse(preceding),
            mapOf("TEST" to "1"),
        )

    private val rateChange = setOf(LocalDate.parse("2024-01-01"))

    @Test
    fun `INV-AC-749 INV-AC-26532 the archived bytes equal the projector output for the stored credit note`() {
        assertArrayEquals(archived, build())
    }

    @Test
    fun `INV-AC-26532 a second build returns the same bytes as the first`() {
        build().contentEquals(build()) shouldBe true
    }

    @Test
    fun `INV-AC-26532 one changed byte of the stored document changes the output`() {
        val changed = stored.replace("\"Thanks\"", "\"Thankz\"")
        assertFalse(changed == stored)
        assertFalse(archived.contentEquals(build(changed)))
    }

    @Test
    fun `INV-AC-26532 the corpus of the stored credit note is Covered`() {
        RegenerationCorpusCoverage.check(listOf(entry("2023-12-01")), rateChange) shouldBe
            CorpusCoverage.Covered(listOf("CN-2024-000045"))
        RegenerationCorpusCoverage.check(listOf(entry("2024-01-01")), rateChange) shouldBe
            CorpusCoverage.NotCovered(CoverageGap.NO_STRADDLING_CREDIT_NOTE)
    }
}
