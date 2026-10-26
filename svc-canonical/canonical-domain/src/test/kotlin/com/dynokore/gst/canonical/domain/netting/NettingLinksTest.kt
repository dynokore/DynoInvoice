package com.dynokore.gst.canonical.domain.netting

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class NettingLinksTest {
    private val entity = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val otherEntity = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
    private val cn1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val cn2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val inv1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val inv2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2")

    private fun doc(
        id: UUID,
        function: DocumentFunction,
        entityId: UUID = entity,
    ) = RecordedDocument(id, entityId, function)

    private fun declaring(
        id: UUID,
        keys: List<String>,
        function: DocumentFunction = DocumentFunction.INVOICE,
        entityId: UUID = entity,
    ) = DeclaringDocument(id, entityId, function, keys)

    @Test
    fun `INV-AC-1764 one key naming a recorded credit note of the entity resolves to its Doc UUID`() {
        val links =
            NettingLinks.forRevision(
                entity,
                listOf("ERP-CN-1"),
                mapOf("ERP-CN-1" to doc(cn1, DocumentFunction.CREDIT_NOTE)),
            )
        links shouldBe RevisionLinks(listOf(cn1), emptyList())
    }

    @Test
    fun `INV-AC-1764 two keys resolve to both Doc UUIDs in key order`() {
        val recorded =
            mapOf(
                "ERP-CN-1" to doc(cn1, DocumentFunction.CREDIT_NOTE),
                "ERP-CN-2" to doc(cn2, DocumentFunction.CREDIT_NOTE),
            )
        NettingLinks.forRevision(entity, listOf("ERP-CN-2", "ERP-CN-1"), recorded).resolved shouldBe listOf(cn2, cn1)
    }

    @Test
    fun `INV-AC-602 a credit note declared by exactly one revised invoice is netted by that invoice`() {
        NettingLinks.forCreditNote(entity, "ERP-CN-1", listOf(declaring(inv1, listOf("ERP-CN-1")))) shouldBe
            NettedBy(inv1)
    }

    @Test
    fun `a key naming nothing is NOT_RECORDED`() {
        NettingLinks.forRevision(entity, listOf("ERP-CN-9"), emptyMap()) shouldBe
            RevisionLinks(emptyList(), listOf(Unresolved("ERP-CN-9", Why.NOT_RECORDED)))
    }

    @Test
    fun `a key naming an invoice is NOT_A_CREDIT_NOTE`() {
        NettingLinks
            .forRevision(
                entity,
                listOf("K"),
                mapOf("K" to doc(inv1, DocumentFunction.INVOICE)),
            ).unresolved shouldBe
            listOf(Unresolved("K", Why.NOT_A_CREDIT_NOTE))
    }

    @Test
    fun `a key naming a debit note is NOT_A_CREDIT_NOTE`() {
        NettingLinks
            .forRevision(
                entity,
                listOf("K"),
                mapOf("K" to doc(inv1, DocumentFunction.DEBIT_NOTE)),
            ).unresolved shouldBe
            listOf(Unresolved("K", Why.NOT_A_CREDIT_NOTE))
    }

    @Test
    fun `a key naming a credit note of another entity is OTHER_ENTITY`() {
        val recorded = mapOf("K" to doc(cn1, DocumentFunction.CREDIT_NOTE, otherEntity))
        val links = NettingLinks.forRevision(entity, listOf("K"), recorded)
        links.resolved.shouldBeEmpty()
        links.unresolved shouldBe listOf(Unresolved("K", Why.OTHER_ENTITY))
    }

    @Test
    fun `unresolved keys keep key order`() {
        val recorded = mapOf("B" to doc(inv1, DocumentFunction.INVOICE))
        NettingLinks.forRevision(entity, listOf("C", "B", "A"), recorded).unresolved.map { it.key } shouldBe
            listOf("C", "B", "A")
    }

    @Test
    fun `a declaring credit note gives NotNetted`() {
        NettingLinks.forCreditNote(
            entity,
            "K",
            listOf(declaring(cn2, listOf("K"), DocumentFunction.CREDIT_NOTE)),
        ) shouldBe
            NotNetted
    }

    @Test
    fun `a declaring invoice of another entity gives NotNetted`() {
        NettingLinks.forCreditNote(entity, "K", listOf(declaring(inv1, listOf("K"), entityId = otherEntity))) shouldBe
            NotNetted
    }

    @Test
    fun `two revised invoices listing the key give Ambiguous`() {
        NettingLinks.forCreditNote(
            entity,
            "K",
            listOf(declaring(inv1, listOf("K")), declaring(inv2, listOf("K", "X"))),
        ) shouldBe
            Ambiguous
    }

    @Test
    fun `no declaring document gives NotNetted`() {
        NettingLinks.forCreditNote(entity, "K", emptyList()) shouldBe NotNetted
    }

    @Test
    fun `an empty keys list gives empty resolved and unresolved`() {
        NettingLinks.forRevision(entity, emptyList(), mapOf("K" to doc(cn1, DocumentFunction.CREDIT_NOTE))) shouldBe
            RevisionLinks(emptyList(), emptyList())
    }

    @Test
    fun `a key listed with different case is not equal`() {
        NettingLinks.forCreditNote(entity, "ERP-CN-1", listOf(declaring(inv1, listOf("erp-cn-1")))) shouldBe NotNetted
        NettingLinks
            .forRevision(
                entity,
                listOf("ERP-CN-1"),
                mapOf("erp-cn-1" to doc(cn1, DocumentFunction.CREDIT_NOTE)),
            ).unresolved shouldBe
            listOf(Unresolved("ERP-CN-1", Why.NOT_RECORDED))
    }

    @Test
    fun `equal input gives equal output`() {
        val recorded = mapOf("K" to doc(cn1, DocumentFunction.CREDIT_NOTE))
        NettingLinks.forRevision(entity, listOf("K"), recorded) shouldBe
            NettingLinks.forRevision(entity, listOf("K"), recorded)
    }

    @Test
    fun `DocumentFunction equals the documentFunction enum of the ingestion contract`() {
        val path = System.getProperty("gst.ingestion.schema")
        path shouldNotBe null
        val text = File(path).readText()
        val block = Regex("\"documentFunction\"\\s*:\\s*\\{[^}]*?\"enum\"\\s*:\\s*\\[([^\\]]*)]").find(text)
        block shouldNotBe null
        val members = Regex("\"([^\"]+)\"").findAll(block!!.groupValues[1]).map { it.groupValues[1] }.toSet()
        DocumentFunction.entries.map { it.name }.toSet() shouldBe members
    }
}
