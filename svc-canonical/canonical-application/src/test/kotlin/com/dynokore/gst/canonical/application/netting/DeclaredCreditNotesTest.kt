package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.PartyIdentifier
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.RecordedDocument
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class DeclaredCreditNotesTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000d$n")

    private val tenant = id(8)
    private val entity = id(9)
    private val noteA = id(1)
    private val invoice = id(2)
    private val otherEntityNote = id(3)

    private val recorded =
        mapOf(
            "CN-A" to RecordedDocument(noteA, entity, DocumentFunction.CREDIT_NOTE),
            "INV-1" to RecordedDocument(invoice, entity, DocumentFunction.INVOICE),
            "CN-X" to RecordedDocument(otherEntityNote, id(7), DocumentFunction.CREDIT_NOTE),
        )
    private val lookups = mutableListOf<Triple<UUID, String, List<String>>>()

    private val resolver =
        NettingLinksDeclaredCreditNotes(
            object : RecordedDocumentsByKey {
                override fun lookup(
                    tenantId: UUID,
                    sourceSystem: String,
                    keys: List<String>,
                ): Map<String, RecordedDocument> {
                    lookups += Triple(tenantId, sourceSystem, keys)
                    return recorded.filterKeys { it in keys }
                }
            },
        )

    private fun command(keys: List<String>) =
        RecordDocumentCommand(
            tenantId = tenant,
            entityRef = "E1",
            sourceSystem = "S1",
            sourceKey = "K1",
            sourceRevision = 1,
            transactionDate = LocalDate.parse("2026-03-20"),
            direction = null,
            supplier = PartyIdentifier("UEN", "SUP-1"),
            buyer = PartyIdentifier("UEN", "BUY-1"),
            invoiceBacked = true,
            nettedCreditNoteKeys = keys,
        )

    @Test
    fun `INV-AC-25341 a key the lookup resolves to a credit note of the same entity gives its Doc UUID`() {
        resolver.of(command(listOf("CN-A")), entity) shouldBe listOf(noteA)
        lookups shouldBe listOf(Triple(tenant, "S1", listOf("CN-A")))
    }

    @Test
    fun `INV-AC-25341 a key naming nothing, another entity or an invoice gives no Doc UUID`() {
        resolver.of(command(listOf("NOPE", "CN-X", "INV-1")), entity) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25341 a key whose text equals a credit note Doc UUID but is not resolved gives no Doc UUID`() {
        resolver.of(command(listOf(noteA.toString())), entity) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25341 an empty key list does not call the lookup`() {
        resolver.of(command(emptyList()), entity) shouldBe emptyList()
        lookups shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25341 a repeated key throws`() {
        assertThrows<IllegalArgumentException> { resolver.of(command(listOf("CN-A", "CN-A")), entity) }
    }
}
