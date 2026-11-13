package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
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

/** The two failure handlers over an in-memory store and fake readers (the Jackson readers are tested in adapters). */
class FailureHandlersTest {
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
    private val d1 = "22222222-2222-2222-2222-222222222222"
    private val d2 = "33333333-3333-3333-3333-333333333333"
    private val t1 = Instant.parse("2026-11-12T08:00:00Z")
    private val t2 = Instant.parse("2026-11-12T09:00:00Z")
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

    private class FakeReader(
        private val d: String,
    ) : SubmissionFailedReader,
        PayloadRejectedReader {
        var seenRef: String? = "unset"
        var seenSeq: Long? = -1

        override fun read(
            dataJson: String,
            envelopeEntityRef: String?,
            envelopeDocSequence: Long?,
        ): FailureFacts {
            seenRef = envelopeEntityRef
            seenSeq = envelopeDocSequence
            if (dataJson == "bad") throw ContractViolation("malformed")
            return when (dataJson) {
                "sf" -> FailureFacts(d, envelopeDocSequence!!, envelopeEntityRef!!, "SRT-006", "", "")
                else -> FailureFacts(d, 5, envelopeEntityRef!!, "PAY-001", "OWN-A", "TERMINAL")
            }
        }
    }

    private val sfReader = FakeReader(d1)
    private val prReader = FakeReader(d2)
    private val sf = SubmissionFailedHandler(tenants, sfReader, RecordDocumentHeld(store))
    private val pr = PayloadRejectedHandler(tenants, prReader, RecordDocumentHeld(store))

    private fun envelope(
        data: String,
        time: Instant,
        entity: EntityRef,
        docSequence: Long?,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/svc",
        type = "com.dynokore.gst.svc.Failure.v1",
        subject = d1,
        time = time,
        tenant = tenantId,
        entity = entity,
        docUuid = DocUuid(UUID.fromString(d1)),
        transactionDate = null,
        docSequence = docSequence,
        instanceId = "svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun sfEvent() = envelope("sf", t1, EntityRef("E1"), 3)

    private fun prEvent() = envelope("pr", t2, EntityRef("E2"), null)

    @Test
    fun `INV-AC-21057 SubmissionFailed opens one row from failureClass with empty owner and raisedAt T1`() {
        sf.handle(sfEvent()) shouldBe HandleOutcome.APPLIED
        store.rows.values.single() shouldBe
            ExceptionRow(
                tenantId.value.toString(),
                store.rows.values
                    .single()
                    .exceptionId,
                "E1",
                d1,
                "SRT-006",
                "",
                "",
                ItemState.OPEN,
                t1,
                "",
                3,
            )
    }

    @Test
    fun `INV-AC-21057 PayloadRejected opens one row with payload docsequence owner disposition and raisedAt T2`() {
        pr.handle(prEvent()) shouldBe HandleOutcome.APPLIED
        val row = store.rows.values.single()
        row.code shouldBe "PAY-001"
        row.entityRef shouldBe "E2"
        row.docUuid shouldBe d2
        row.docsequence shouldBe 5
        row.periodRef shouldBe ""
        row.remediationOwner shouldBe "OWN-A"
        row.disposition shouldBe "TERMINAL"
        row.raisedAt shouldBe t2
        row.state shouldBe ItemState.OPEN
    }

    @Test
    fun `INV-AC-21057 repeats of both events change nothing and return IGNORED`() {
        sf.handle(sfEvent())
        pr.handle(prEvent())
        val before = store.rows.toMap()
        sf.handle(sfEvent()) shouldBe HandleOutcome.IGNORED
        pr.handle(prEvent()) shouldBe HandleOutcome.IGNORED
        store.rows shouldBe before
    }

    @Test
    fun `INV-AC-21057 tenant-wide and platform envelope entities are passed as absent`() {
        val platformType = "com.dynokore.gst.platform.PolicyChanged.v1"
        val base = envelope("sf", t1, EntityRef("E1"), 3).copy(entity = EntityRef.TENANT_WIDE, type = platformType)
        shouldThrow<NullPointerException> { sf.handle(base) }
        sfReader.seenRef shouldBe null
        sfReader.seenRef = "unset"
        shouldThrow<NullPointerException> {
            sf.handle(
                base.copy(entity = EntityRef.PLATFORM, tenant = TenantId.PLATFORM),
            )
        }
        sfReader.seenRef shouldBe null
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-21057 a ContractViolation writes nothing`() {
        shouldThrow<ContractViolation> { sf.handle(envelope("bad", t1, EntityRef("E1"), 3)) }
        shouldThrow<ContractViolation> { pr.handle(envelope("bad", t2, EntityRef("E2"), null)) }
        store.rows.isEmpty() shouldBe true
    }
}
