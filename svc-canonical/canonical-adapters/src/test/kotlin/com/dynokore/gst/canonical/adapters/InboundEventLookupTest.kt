package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.domain.inbound.BuyerDisposition
import com.dynokore.gst.canonical.domain.inbound.Existing
import com.dynokore.gst.canonical.domain.inbound.FallbackKey
import com.dynokore.gst.canonical.domain.inbound.InboundEvent
import com.dynokore.gst.canonical.domain.inbound.InboundKeys
import com.dynokore.gst.canonical.domain.inbound.InboundKind
import com.dynokore.gst.canonical.domain.inbound.UuidKey
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The lookups over `canonical.inbound_event` (INV-T-6700; INV-REQ-429, INV-REQ-430, INV-REQ-569). */
class InboundEventLookupTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val endpoint = "0088:5790000000001"
    private val supplierUuid = UUID.randomUUID()

    @Suppress("LongParameterList") // a test builder: every argument has a default
    private fun receipt(
        number: String = "INV-001",
        year: Int = 2026,
        within: UUID = entity,
        function: DocumentFunction = DocumentFunction.INVOICE,
        scheme: String = "0088",
        value: String = "5790000000001",
        uuid: UUID? = supplierUuid,
        at: String = endpoint,
        disposition: BuyerDisposition = BuyerDisposition.PENDING,
    ) = InboundEvent(
        InboundKind.RECEIPT,
        within,
        function,
        scheme,
        value,
        number,
        LocalDate.of(year, 3, 1),
        at,
        uuid,
        disposition,
    )

    private fun posting(
        number: String = "INV-001",
        year: Int = 2026,
        scheme: String = "0088",
        value: String = "5790000000001",
    ) = InboundEvent(
        InboundKind.POSTING,
        entity,
        DocumentFunction.INVOICE,
        scheme,
        value,
        number,
        LocalDate.of(year, 3, 1),
    )

    private fun append(
        event: InboundEvent,
        doc: UUID,
        version: Int = 1,
        owner: UUID = tenant,
    ) = kit.asTenant(owner) { kit.transaction.inTransaction { kit.inbound.append(event, doc, version) } }

    private fun byFallback(
        event: InboundEvent,
        asTenant: UUID = tenant,
        edit: (FallbackKey) -> FallbackKey = { it },
    ): List<Existing> {
        val key = edit(checkNotNull(InboundKeys.fallbackKey(event)))
        return kit.asTenant(asTenant) { kit.transaction.inTransaction { kit.inbound.findByFallbackKey(key) } }
    }

    private fun byUuid(
        edit: (UuidKey) -> UuidKey = { it },
        asTenant: UUID = tenant,
    ): List<Existing> {
        val key = edit(checkNotNull(InboundKeys.uuidKey(receipt())))
        return kit.asTenant(asTenant) { kit.transaction.inTransaction { kit.inbound.findByUuidKey(key) } }
    }

    private fun previous(
        doc: UUID,
        asTenant: UUID = tenant,
    ) = kit.asTenant(asTenant) { kit.transaction.inTransaction { kit.inbound.previousDisposition(doc) } }

    private fun hit(doc: UUID) = listOf(Existing(doc, null))

    @Test
    fun `INV-AC-1431 a posting row is found by the fallback key of a receipt of the same supplier, number and year`() {
        val doc = UUID.randomUUID()
        append(posting(), doc)
        byFallback(receipt(uuid = null)) shouldBe hit(doc)
    }

    @Test
    fun `INV-AC-1431 a receipt row with a UUID is found by the fallback key of the posting`() {
        val doc = UUID.randomUUID()
        append(receipt(), doc)
        byFallback(posting()) shouldBe listOf(Existing(doc, supplierUuid))
    }

    @Test
    fun `INV-AC-1432 findByUuidKey finds the Doc UUID of a receipt row and still one after version 2`() {
        val doc = UUID.randomUUID()
        append(receipt(), doc)
        byUuid() shouldBe listOf(Existing(doc, supplierUuid))
        append(receipt(number = "INV-001-B"), doc, version = 2)
        byUuid() shouldBe listOf(Existing(doc, supplierUuid))
    }

    @Test
    fun `INV-AC-1578 rows for 2027 and 2028 with the same number are found only by their own year`() {
        val d2027 = UUID.randomUUID()
        val d2028 = UUID.randomUUID()
        append(receipt(year = 2027, uuid = null), d2027)
        append(receipt(year = 2028, uuid = null), d2028)
        byFallback(receipt(year = 2027)) shouldBe hit(d2027)
        byFallback(receipt(year = 2028)) shouldBe hit(d2028)
    }

    @Test
    fun `INV-REQ-569 a posting alone is stored as POSTING with null receipt columns and found by fallback key`() {
        val doc = UUID.randomUUID()
        append(posting(), doc)
        DocumentSql.rows(
            "select event_kind, supplier_endpoint_id, supplier_doc_uuid, buyer_disposition " +
                "from canonical.inbound_event where doc_uuid = ?",
            doc,
        ) shouldBe listOf(listOf("POSTING", null, null, null))
        previous(doc) shouldBe null
        byFallback(posting()) shouldBe hit(doc)
    }

    @Test
    fun `a receipt with a UUID and a blank supplier value has null fallback columns, found by UUID and by no key`() {
        val doc = UUID.randomUUID()
        append(receipt(value = " "), doc)
        DocumentSql.rows(
            "select supplier_key, document_number, document_year from canonical.inbound_event where doc_uuid = ?",
            doc,
        ) shouldBe listOf(listOf(null, null, null))
        byUuid() shouldBe listOf(Existing(doc, supplierUuid))
        byFallback(receipt()) shouldBe emptyList()
    }

    @Test
    fun `append writes the key columns as InboundKeys returns them`() {
        val doc = UUID.randomUUID()
        append(receipt(number = " INV-001 ", scheme = "0088 ", value = " ABC9 "), doc)
        DocumentSql.rows(
            "select supplier_key, document_number, document_year from canonical.inbound_event where doc_uuid = ?",
            doc,
        ) shouldBe listOf(listOf("0088:abc9", "INV-001", "2026"))
    }

    @Test
    fun `INV-AC-621 previousDisposition returns the disposition of version 2 and null for a posting only document`() {
        val doc = UUID.randomUUID()
        append(receipt(disposition = BuyerDisposition.PENDING), doc, version = 1)
        append(receipt(disposition = BuyerDisposition.ACCEPTED), doc, version = 2)
        previous(doc) shouldBe BuyerDisposition.ACCEPTED
        val postingOnly = UUID.randomUUID()
        append(posting(), postingOnly)
        previous(postingOnly) shouldBe null
    }

    @Test
    fun `previousDisposition ignores a later POSTING version and orders by version`() {
        val doc = UUID.randomUUID()
        append(receipt(disposition = BuyerDisposition.REJECTED), doc, version = 2)
        append(receipt(disposition = BuyerDisposition.PENDING), doc, version = 1)
        append(posting(), doc, version = 3)
        previous(doc) shouldBe BuyerDisposition.REJECTED
    }

    @Test
    fun `look-alike another entity finds nothing`() {
        append(receipt(uuid = null), UUID.randomUUID())
        byFallback(receipt()) { it.copy(entityId = UUID.randomUUID()) } shouldBe emptyList()
        byUuid({ it.copy(entityId = UUID.randomUUID()) }) shouldBe emptyList()
    }

    @Test
    fun `look-alike another tenant through the session finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byFallback(receipt(), UUID.randomUUID()) shouldBe emptyList()
        byUuid(asTenant = UUID.randomUUID()) shouldBe emptyList()
    }

    @Test
    fun `look-alike another document function finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byFallback(receipt()) { it.copy(documentFunction = DocumentFunction.CREDIT_NOTE) } shouldBe emptyList()
    }

    @Test
    fun `look-alike another supplier key finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byFallback(receipt(value = "5790000000002")) shouldBe emptyList()
        byFallback(receipt(scheme = "0195")) shouldBe emptyList()
    }

    @Test
    fun `look-alike a number differing in case finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byFallback(receipt(number = "inv-001")) shouldBe emptyList()
    }

    @Test
    fun `look-alike a number with a trailing character finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byFallback(receipt(number = "INV-0011")) shouldBe emptyList()
    }

    @Test
    fun `look-alike another year finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byFallback(receipt(year = 2025)) shouldBe emptyList()
    }

    @Test
    fun `look-alike an endpoint id differing by one character finds nothing`() {
        append(receipt(), UUID.randomUUID())
        byUuid({ it.copy(supplierEndpointId = endpoint.dropLast(1) + "2") }) shouldBe emptyList()
    }

    @Test
    fun `look-alike a UUID differing by one character finds nothing`() {
        append(receipt(), UUID.randomUUID())
        val text = supplierUuid.toString()
        val near = UUID.fromString(text.dropLast(1) + if (text.last() == '0') '1' else '0')
        byUuid({ it.copy(supplierDocUuid = near) }) shouldBe emptyList()
    }

    @Test
    fun `a document whose version 2 changed the number is found only by the key of version 2`() {
        val doc = UUID.randomUUID()
        append(receipt(number = "INV-001", uuid = null), doc, version = 1)
        append(receipt(number = "INV-002", uuid = null), doc, version = 2)
        byFallback(receipt(number = "INV-001")) shouldBe emptyList()
        byFallback(receipt(number = "INV-002")) shouldBe hit(doc)
    }

    @Test
    fun `two Doc UUIDs with one key return two Existing`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        append(posting(), first)
        append(posting(), second)
        byFallback(posting()).map { it.docUuid }.toSet() shouldBe setOf(first, second)
    }

    @Test
    fun `recordedSupplierDocUuid is the one of the highest version row that has one`() {
        val doc = UUID.randomUUID()
        val later = UUID.randomUUID()
        append(receipt(uuid = supplierUuid), doc, version = 1)
        append(receipt(uuid = later), doc, version = 2)
        append(posting(), doc, version = 3)
        byFallback(posting()) shouldBe listOf(Existing(doc, later))
    }

    @Test
    fun `findByUuidKey returns a row of tenant A to tenant A and nothing to tenant B`() {
        val doc = UUID.randomUUID()
        append(receipt(), doc)
        byUuid() shouldBe listOf(Existing(doc, supplierUuid))
        byUuid(asTenant = UUID.randomUUID()) shouldBe emptyList()
    }

    @Test
    fun `findByUuidKey with no tenant bound throws TenantPredicateViolation`() {
        val key = checkNotNull(InboundKeys.uuidKey(receipt()))
        // a transaction is open for a tenant, but the thread that reads has no tenant bound
        kit.asTenant(tenant) {
            kit.transaction.inTransaction {
                var failure: Throwable? = null
                val reader = Thread { failure = runCatching { kit.inbound.findByUuidKey(key) }.exceptionOrNull() }
                reader.start()
                reader.join()
                (failure is TenantPredicateViolation) shouldBe true
            }
        }
    }
}
