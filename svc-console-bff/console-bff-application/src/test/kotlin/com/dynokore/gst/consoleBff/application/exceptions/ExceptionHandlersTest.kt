package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The two handlers over an in-memory store and fake readers (the Jackson readers are tested in adapters). */
class ExceptionHandlersTest {
    private class MemoryStore : ExceptionStore {
        val rows = linkedMapOf<Pair<String, UUID>, ExceptionRow>()

        override fun upsert(rows: List<ExceptionRow>) {
            rows.forEach { this.rows[it.tenantId to it.exceptionId] = it }
        }

        override fun rowsOfDocument(
            tenantId: String,
            docUuid: String,
        ) = rows.values.filter { it.tenantId == tenantId && it.docUuid == docUuid }

        override fun openRowsOfDocument(
            tenantId: String,
            docUuid: String,
        ) = rowsOfDocument(tenantId, docUuid).filter { it.state == ItemState.OPEN }
    }

    private val tenantId = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val doc = "22222222-2222-2222-2222-222222222222"
    private val t1 = Instant.parse("2026-11-12T08:00:00Z")
    private val clock = Instant.parse("2030-01-01T00:00:00Z")
    private val store = MemoryStore()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenantId

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    /** dataJson "payload:X" carries entityRef X, "none" carries none, "bad" is malformed. */
    private val heldReader =
        object : DocumentHeldReader {
            var lastFallback: String? = "unset"

            override fun read(
                dataJson: String,
                envelopeEntityRef: String?,
            ): DocumentHeldFacts {
                lastFallback = envelopeEntityRef
                if (dataJson == "bad") throw ContractViolation("malformed")
                val payloadRef = dataJson.removePrefix("payload:").takeIf { dataJson.startsWith("payload:") }
                val ref = payloadRef ?: envelopeEntityRef ?: throw ContractViolation("no entityRef")
                return DocumentHeldFacts(
                    ref,
                    doc,
                    1L,
                    listOf("PAY-001", "MDM-002"),
                    "2026-Q3",
                    "PAY-001",
                    "Tax operations",
                    "MANUAL",
                )
            }
        }

    private val releasedReader =
        object : DocumentReleasedReader {
            override fun read(dataJson: String): DocumentReleasedFacts {
                if (dataJson == "bad") throw ContractViolation("malformed")
                return DocumentReleasedFacts(dataJson.substringBefore('|'), 2L, dataJson.substringAfter('|').split(','))
            }
        }

    private val held = DocumentHeldHandler(tenants, heldReader, RecordDocumentHeld(store))
    private val released = DocumentReleasedHandler(tenants, releasedReader, RecordDocumentReleased(store))

    private fun envelope(
        type: String,
        data: String,
        entity: EntityRef = EntityRef("ENT-ENVELOPE"),
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/canonical-svc",
        type = "com.dynokore.gst.canonical.$type.v1",
        subject = doc,
        time = t1,
        tenant = tenantId,
        entity = entity,
        docUuid = DocUuid(UUID.fromString(doc)),
        transactionDate = null,
        docSequence = 1,
        instanceId = "canonical-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun state(code: String) =
        store.rows.values
            .single { it.code == code }
            .state

    @Test
    fun `INV-AC-21027 first hold stores two OPEN rows raised at the envelope time and the repeat is IGNORED`() {
        held.handle(envelope("DocumentHeld", "payload:ENT-P")) shouldBe HandleOutcome.APPLIED
        store.rows.size shouldBe 2
        store.rows.values
            .map { it.state }
            .toSet() shouldBe setOf(ItemState.OPEN)
        val before = store.rows.values.toList()
        held.handle(envelope("DocumentHeld", "payload:ENT-P")) shouldBe HandleOutcome.IGNORED
        store.rows.values.toList() shouldBe before
    }

    @Test
    fun `INV-AC-21027 raisedAt equals the envelope time and not the clock`() {
        held.handle(envelope("DocumentHeld", "payload:ENT-P"))
        store.rows.values.all { it.raisedAt == t1 } shouldBe true
        store.rows.values.any { it.raisedAt == clock } shouldBe false
    }

    @Test
    fun `INV-AC-21027 release resolves the PAY-001 row only and a no-match release is IGNORED`() {
        held.handle(envelope("DocumentHeld", "payload:ENT-P"))
        released.handle(envelope("DocumentReleased", "$doc|PAY-001")) shouldBe HandleOutcome.APPLIED
        state("PAY-001") shouldBe ItemState.RESOLVED
        state("MDM-002") shouldBe ItemState.OPEN
        released.handle(envelope("DocumentReleased", "$doc|XXX-999")) shouldBe HandleOutcome.IGNORED
        released.handle(envelope("DocumentReleased", "$doc|PAY-001")) shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-21027 a release of another docUuid resolves no row of this document`() {
        held.handle(envelope("DocumentHeld", "payload:ENT-P"))
        released.handle(envelope("DocumentReleased", "33333333-3333-3333-3333-333333333333|PAY-001")) shouldBe
            HandleOutcome.IGNORED
        state("PAY-001") shouldBe ItemState.OPEN
    }

    @Test
    fun `INV-AC-21027 the stored entityRef is the payload value when both are present and different`() {
        held.handle(envelope("DocumentHeld", "payload:ENT-P", EntityRef("ENT-ENVELOPE")))
        store.rows.values
            .map { it.entityRef }
            .toSet() shouldBe setOf("ENT-P")
    }

    @Test
    fun `INV-AC-21027 the stored entityRef is the envelope entity value when the payload has none`() {
        held.handle(envelope("DocumentHeld", "none", EntityRef("ENT-ENVELOPE")))
        store.rows.values
            .map { it.entityRef }
            .toSet() shouldBe setOf("ENT-ENVELOPE")
    }

    @Test
    fun `INV-AC-21027 tenant-wide and platform envelope entities are passed as null and write nothing`() {
        val platformType = "com.dynokore.gst.platform.PolicyChanged.v1"
        val base = envelope("DocumentHeld", "none").copy(entity = EntityRef.TENANT_WIDE, type = platformType)
        shouldThrow<ContractViolation> { held.handle(base) }
        heldReader.lastFallback shouldBe null
        heldReader.lastFallback = "unset"
        shouldThrow<ContractViolation> {
            held.handle(
                base.copy(entity = EntityRef.PLATFORM, tenant = TenantId.PLATFORM),
            )
        }
        heldReader.lastFallback shouldBe null
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21027 a ContractViolation writes nothing`() {
        shouldThrow<ContractViolation> { held.handle(envelope("DocumentHeld", "bad")) }
        shouldThrow<ContractViolation> { released.handle(envelope("DocumentReleased", "bad")) }
        store.rows.size shouldBe 0
    }
}
