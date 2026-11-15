package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.DespatchTestDb
import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.DESPATCH_FACT
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** INV-AC-16774, the consumer half: the handler keeps the first fact per (tenant, entity) from envelope attributes. */
class SubmissionDespatchedHandlerTest {
    private val time = Instant.parse("2026-11-02T03:04:05Z")

    private fun event(
        tenant: TenantId,
        entity: String,
        doc: UUID = UUID.randomUUID(),
        data: String = "not read",
        type: String = TenancyEventCatalogue.SUBMISSION_DESPATCHED_TYPE,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
        source = "/gst/submission-svc",
        type = type,
        subject = doc.toString(),
        time = time,
        tenant = tenant,
        entity = EventEntityRef(entity),
        docUuid = DocUuid(doc),
        transactionDate = null,
        docSequence = null,
        instanceId = "submission-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun handle(
        tenant: TenantId,
        event: Envelope,
    ): HandleOutcome =
        DespatchTestDb.bound(tenant) { dsl -> SubmissionDespatchedHandler(JooqDespatchFactStore(dsl)).handle(event) }

    private fun rows(tenant: TenantId) =
        DespatchTestDb.superuser { dsl ->
            dsl.selectFrom(DESPATCH_FACT).where(DESPATCH_FACT.TENANT_ID.eq(tenant.value)).fetch()
        }

    @Test
    fun `INV-AC-16774 the first event of an entity inserts one row with the event time and the Doc UUID`() {
        val tenant = DespatchTestDb.tenant()
        val doc = UUID.randomUUID()
        handle(tenant, event(tenant, "E-1", doc)) shouldBe HandleOutcome.APPLIED
        val row = rows(tenant).single()
        row.entityRef shouldBe "E-1"
        row.rowKind shouldBe JooqDespatchFactStore.ENTITY
        row.docUuid shouldBe doc
        row.firstDespatchedAt!!.toInstant() shouldBe time
    }

    @Test
    fun `INV-AC-16774 a second event for the same pair and the same event twice change nothing`() {
        val tenant = DespatchTestDb.tenant()
        val first = event(tenant, "E-1")
        handle(tenant, first) shouldBe HandleOutcome.APPLIED
        handle(tenant, first) shouldBe HandleOutcome.IGNORED
        handle(tenant, event(tenant, "E-1")) shouldBe HandleOutcome.IGNORED
        rows(tenant).map { it.docUuid } shouldBe listOf(first.docUuid!!.value)
    }

    @Test
    fun `INV-AC-16774 another entity of the tenant gets its own row`() {
        val tenant = DespatchTestDb.tenant()
        handle(tenant, event(tenant, "E-1")) shouldBe HandleOutcome.APPLIED
        handle(tenant, event(tenant, "E-2")) shouldBe HandleOutcome.APPLIED
        rows(tenant).map { it.entityRef }.toSet() shouldBe setOf("E-1", "E-2")
    }

    @Test
    fun `INV-AC-16774 the payload is never read and an event of another type is ignored`() {
        val tenant = DespatchTestDb.tenant()
        handle(tenant, event(tenant, "E-1", data = "{not json")) shouldBe HandleOutcome.APPLIED
        handle(tenant, event(tenant, "E-2", type = "com.dynokore.gst.submission.SubmissionFailed.v1")) shouldBe
            HandleOutcome.IGNORED
        rows(tenant).map { it.entityRef } shouldBe listOf("E-1")
    }

    @Test
    fun `INV-AC-16774 an envelope of the platform tenant is a contract violation and writes nothing`() {
        val tenant = DespatchTestDb.tenant()
        val platform =
            Envelope(
                id = EventId("ev-platform"),
                source = "/gst/submission-svc",
                type = "com.dynokore.gst.platform.ReplayCompleted.v1",
                subject = "s",
                time = time,
                tenant = TenantId.PLATFORM,
                entity = EventEntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "submission-svc-0",
                correlation = CorrelationId("c"),
                causation = CausationId("c"),
                dataJson = "{}",
            )
        shouldThrow<ContractViolation> { handle(tenant, platform) }
        rows(tenant).size shouldBe 0
    }

    @Test
    fun `INV-AC-16774 the unattributed marker is written through the store and a second one changes nothing`() {
        val tenant = DespatchTestDb.tenant()
        DespatchTestDb.bound(tenant) { dsl ->
            val store = JooqDespatchFactStore(dsl)
            store.keepUnattributedMarker(tenant, time, null) shouldBe true
            store.keepUnattributedMarker(tenant, time, null) shouldBe false
        }
        rows(tenant).single().rowKind shouldBe JooqDespatchFactStore.UNATTRIBUTED
    }
}
