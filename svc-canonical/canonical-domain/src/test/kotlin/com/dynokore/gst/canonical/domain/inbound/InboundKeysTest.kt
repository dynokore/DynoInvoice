package com.dynokore.gst.canonical.domain.inbound

import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class InboundKeysTest {
    private val entity = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val base =
        InboundEvent(
            kind = InboundKind.RECEIPT,
            entityId = entity,
            documentFunction = DocumentFunction.INVOICE,
            supplierScheme = "UEN",
            supplierValue = "201912345A",
            documentNumber = "INV-001",
            transactionDate = LocalDate.of(2027, 3, 1),
            supplierEndpointId = "0195:201912345A",
            supplierDocUuid = uuid,
            buyerDisposition = BuyerDisposition.ACCEPTED,
        )

    private fun key(event: InboundEvent) = InboundKeys.fallbackKey(event)

    @Test
    fun `INV-AC-1431 the UUID key of a receipt with a UUID, null without one and on a posting`() {
        InboundKeys.uuidKey(base) shouldBe UuidKey(entity, "0195:201912345A", uuid)
        InboundKeys.uuidKey(base.copy(supplierDocUuid = null)) shouldBe null
        InboundKeys.uuidKey(base.copy(kind = InboundKind.POSTING, supplierEndpointId = null)) shouldBe null
    }

    @Test
    fun `INV-AC-1431 the fallback key joins trimmed lower-cased scheme and value by a colon`() {
        key(base) shouldBe FallbackKey(entity, DocumentFunction.INVOICE, "uen:201912345a", "INV-001", 2027)
    }

    @Test
    fun `INV-AC-1431 supplier value with other case or surrounding blanks is equal`() {
        key(base.copy(supplierValue = "  201912345a ")) shouldBe key(base)
        key(base.copy(supplierScheme = " uen")) shouldBe key(base)
    }

    @Test
    fun `INV-AC-1578 the document number with other case is unequal, surrounding blanks are trimmed`() {
        key(base.copy(documentNumber = "inv-001")) shouldNotBe key(base)
        key(base.copy(documentNumber = " INV-001 ")) shouldBe key(base)
    }

    @Test
    fun `INV-AC-1431 a different function, entity or supplier scheme is unequal`() {
        key(base.copy(documentFunction = DocumentFunction.CREDIT_NOTE)) shouldNotBe key(base)
        key(base.copy(entityId = UUID.randomUUID())) shouldNotBe key(base)
        key(base.copy(supplierScheme = "GST")) shouldNotBe key(base)
    }

    @Test
    fun `INV-AC-1431 blank supplier value, blank scheme and blank number give a null fallback key`() {
        key(base.copy(supplierValue = "  ")) shouldBe null
        key(base.copy(supplierScheme = "")) shouldBe null
        key(base.copy(documentNumber = " ")) shouldBe null
    }

    @Test
    fun `INV-AC-1432 events differing only in buyerDisposition have equal keys`() {
        val other = base.copy(buyerDisposition = BuyerDisposition.REJECTED)
        InboundKeys.uuidKey(other) shouldBe InboundKeys.uuidKey(base)
        key(other) shouldBe key(base)
    }
}
