package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.election.ExemptPurchaseAuditRecord
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriteFailed
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The exempt-purchase entry of INV-REQ-369 goes through the same writer as the other election entries. */
class AuditLogElectionAuditTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val instant = Instant.parse("2026-10-01T09:00:00Z")
    private val entity = "E"

    private class Recording(
        private val failure: RuntimeException? = null,
    ) : AuditWriter {
        val entries = mutableListOf<Pair<AuditEntry, EventContext>>()

        override fun record(
            entry: AuditEntry,
            context: EventContext,
        ): Envelope {
            failure?.let { throw it }
            entries += entry to context
            return Envelope(
                EventId("e-1"),
                "/gst/tenancy-svc",
                "com.dynokore.gst.platform.AuditEntryRecorded.v1",
                context.aggregateKey,
                Instant.parse("2026-10-01T09:00:00Z"),
                context.tenant,
                context.entity,
                null,
                null,
                null,
                "i-1",
                context.correlation,
                context.causation,
                "{}",
            )
        }
    }

    private fun tenants(): TenantContext =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()

            override fun currentActor(): String? = null
        }

    private fun record(
        codes: List<String>,
        by: String = "user-u1",
    ) = ExemptPurchaseAuditRecord(entity, codes, by, instant)

    @Test
    fun `INV-AC-18241 one exempt-purchase entry names the user, the sorted codes and tenant T`() {
        val writer = Recording()
        AuditLogElectionAudit(writer, tenants()).exemptPurchaseRecorded(record(listOf("TX-N33", "TX-ESS")))
        writer.entries.size shouldBe 1
        val (entry, context) = writer.entries.single()
        entry shouldBe
            AuditEntry(
                actor = "user-u1",
                action = "election.exempt-purchase.recorded",
                scope = "E",
                reason = "EXEMPT_PURCHASE_CODES recorded for TX-ESS,TX-N33 at 2026-10-01T09:00:00Z",
            )
        context.tenant shouldBe tenant
        context.entity shouldBe EntityRef("E")
        context.aggregateKey shouldBe "E"
    }

    @Test
    fun `INV-AC-18241 a failing writer's exception propagates unchanged`() {
        val failure = AuditWriteFailed(IllegalStateException("db down"))
        val thrown =
            runCatching {
                AuditLogElectionAudit(Recording(failure), tenants()).exemptPurchaseRecorded(record(listOf("TX-N33")))
            }.exceptionOrNull()
        (thrown === failure) shouldBe true
    }

    @Test
    fun `INV-AC-18241 the action constant equals the text and not its look-alikes`() {
        AuditLogElectionAudit.EXEMPT_PURCHASE_RECORDED shouldBe "election.exempt-purchase.recorded"
        AuditLogElectionAudit.EXEMPT_PURCHASE_RECORDED shouldNotBe "election.exempt-purchase.recorded "
        AuditLogElectionAudit.EXEMPT_PURCHASE_RECORDED shouldNotBe "election-change.recorded"
        AuditLogElectionAudit.EXEMPT_PURCHASE_RECORDED shouldNotBe "ELECTION.EXEMPT-PURCHASE.RECORDED"
    }

    @Test
    fun `INV-AC-18241 the actor is the user id text and not a role name`() {
        val writer = Recording()
        AuditLogElectionAudit(writer, tenants()).exemptPurchaseRecorded(record(listOf("TX-N33")))
        writer.entries
            .single()
            .first.actor shouldBe "user-u1"
        writer.entries
            .single()
            .first.actor shouldNotBe "entity.admin"
    }

    @Test
    fun `INV-AC-18241 one code gives that code and two codes are sorted ascending`() {
        val writer = Recording()
        val audit = AuditLogElectionAudit(writer, tenants())
        audit.exemptPurchaseRecorded(record(listOf("TX-N33")))
        audit.exemptPurchaseRecorded(record(listOf("TX-N33", "TX-ESS")))
        writer.entries.map { it.first.reason } shouldBe
            listOf(
                "EXEMPT_PURCHASE_CODES recorded for TX-N33 at 2026-10-01T09:00:00Z",
                "EXEMPT_PURCHASE_CODES recorded for TX-ESS,TX-N33 at 2026-10-01T09:00:00Z",
            )
    }

    @Test
    fun `INV-AC-18241 requested and approved keep their actions`() {
        AuditLogElectionAudit.REQUESTED shouldBe "election-change.requested"
        AuditLogElectionAudit.APPROVED shouldBe "election-change.approved"
    }
}
