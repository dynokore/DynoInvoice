package com.dynokore.gst.canonical.domain.inbound

import com.dynokore.gst.canonical.domain.document.DocUuidSource
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class InboundCorrelationTest {
    private val entity = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val supplierUuid = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun receipt(
        uuid: UUID? = supplierUuid,
        date: LocalDate = LocalDate.of(2027, 3, 1),
        number: String = "INV-001",
        disposition: BuyerDisposition = BuyerDisposition.ACCEPTED,
    ) = InboundEvent(
        kind = InboundKind.RECEIPT,
        entityId = entity,
        documentFunction = DocumentFunction.INVOICE,
        supplierScheme = "UEN",
        supplierValue = "201912345A",
        documentNumber = number,
        transactionDate = date,
        supplierEndpointId = "0195:201912345A",
        supplierDocUuid = uuid,
        buyerDisposition = disposition,
    )

    private fun posting(date: LocalDate = LocalDate.of(2027, 3, 1)) =
        receipt(
            uuid = null,
            date = date,
        ).copy(kind = InboundKind.POSTING, supplierEndpointId = null, buyerDisposition = null)

    private fun decide(
        event: InboundEvent,
        uuidHits: List<Existing> = emptyList(),
        fallbackHits: List<Existing> = emptyList(),
        rule: InboundDocUuidRule = InboundDocUuidRule.ADOPT_SUPPLIER,
    ) = InboundCorrelation.decide(event, uuidHits, fallbackHits, rule)

    private fun hit(
        id: UUID,
        recorded: UUID? = null,
    ) = Existing(id, recorded)

    @Test
    fun `INV-AC-1431 INV-AC-10350 posting recorded first then a receipt with a UUID binds via the fallback key`() {
        decide(receipt(), fallbackHits = listOf(hit(a))) shouldBe NewVersionOf(a, BindVia.FALLBACK_KEY)
    }

    @Test
    fun `INV-AC-1431 INV-AC-10350 receipt first then the posting binds via the fallback key and has no UUID key`() {
        decide(posting(), fallbackHits = listOf(hit(a, supplierUuid))) shouldBe NewVersionOf(a, BindVia.FALLBACK_KEY)
        InboundKeys.uuidKey(posting()) shouldBe null
    }

    @Test
    fun `INV-AC-1431 case b receipt without a UUID binds via the fallback key`() {
        decide(receipt(uuid = null), fallbackHits = listOf(hit(a))) shouldBe NewVersionOf(a, BindVia.FALLBACK_KEY)
    }

    @Test
    fun `INV-AC-1432 INV-AC-10351 one UUID hit binds via the UUID key whatever two fallback hits say`() {
        decide(receipt(), listOf(hit(a)), listOf(hit(b), hit(c))) shouldBe NewVersionOf(a, BindVia.UUID_KEY)
    }

    @Test
    fun `INV-AC-1432 one UUID hit binds via the UUID key when the fallback hit is another document`() {
        decide(receipt(), listOf(hit(a)), listOf(hit(b))) shouldBe NewVersionOf(a, BindVia.UUID_KEY)
    }

    @Test
    fun `INV-AC-1432 two UUID hits are held MULTIPLE_MATCH with both Doc UUIDs and no fallback hit`() {
        decide(receipt(), listOf(hit(a), hit(b))) shouldBe HoldAmbiguous(HoldReason.MULTIPLE_MATCH, listOf(a, b))
    }

    @Test
    fun `INV-AC-1432 two UUID hits are held MULTIPLE_MATCH with both Doc UUIDs when one fallback hit exists`() {
        decide(receipt(), listOf(hit(a), hit(b)), listOf(hit(c))) shouldBe
            HoldAmbiguous(HoldReason.MULTIPLE_MATCH, listOf(a, b))
    }

    @Test
    fun `INV-AC-1578 fallback keys differing only in the year are unequal and no hits create a document for each`() {
        val first = receipt(uuid = null, date = LocalDate.of(2027, 12, 31))
        val second = receipt(uuid = null, date = LocalDate.of(2028, 1, 1))
        InboundKeys.fallbackKey(first) shouldNotBe InboundKeys.fallbackKey(second)
        decide(first) shouldBe CreateDocument(DocUuidSource.SYSTEM, null)
        decide(second) shouldBe CreateDocument(DocUuidSource.SYSTEM, null)
    }

    @Test
    fun `INV-AC-1578 the same year gives equal fallback keys`() {
        InboundKeys.fallbackKey(receipt(date = LocalDate.of(2027, 1, 1))) shouldBe
            InboundKeys.fallbackKey(receipt(date = LocalDate.of(2027, 12, 31)))
    }

    @Test
    fun `INV-AC-10350 two fallback hits are held MULTIPLE_MATCH with both Doc UUIDs`() {
        decide(receipt(uuid = null), fallbackHits = listOf(hit(a), hit(b))) shouldBe
            HoldAmbiguous(HoldReason.MULTIPLE_MATCH, listOf(a, b))
        HoldAmbiguous.CODE shouldBe "ING-017"
    }

    @Test
    fun `INV-AC-10351 one fallback hit with a different recorded supplier UUID is SUPPLIER_UUID_CONFLICT`() {
        decide(receipt(), fallbackHits = listOf(hit(a, recorded = UUID.randomUUID()))) shouldBe
            HoldAmbiguous(HoldReason.SUPPLIER_UUID_CONFLICT, listOf(a))
    }

    @Test
    fun `INV-AC-10351 one fallback hit with the same recorded supplier UUID binds`() {
        decide(receipt(), fallbackHits = listOf(hit(a, recorded = supplierUuid))) shouldBe
            NewVersionOf(a, BindVia.FALLBACK_KEY)
    }

    @Test
    fun `INV-AC-1431 one fallback hit with a null recorded UUID binds`() {
        decide(receipt(), fallbackHits = listOf(hit(a, recorded = null))) shouldBe NewVersionOf(a, BindVia.FALLBACK_KEY)
    }

    @Test
    fun `INV-AC-622 ADOPT_SUPPLIER with a UUID creates a SUPPLIER document with that UUID`() {
        decide(receipt()) shouldBe CreateDocument(DocUuidSource.SUPPLIER, supplierUuid)
    }

    @Test
    fun `INV-AC-622 OWN creates a SYSTEM document`() {
        decide(receipt(), rule = InboundDocUuidRule.OWN) shouldBe CreateDocument(DocUuidSource.SYSTEM, null)
    }

    @Test
    fun `INV-AC-622 ADOPT_SUPPLIER on a posting creates a SYSTEM document`() {
        decide(posting()) shouldBe CreateDocument(DocUuidSource.SYSTEM, null)
    }
}
