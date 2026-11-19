package com.dynokore.gst.evidence.application.lookup

import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20779: the DECISION_TRACE item of the chain answer, with fakes for the ports. */
class ChainDecisionTraceItemTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val user = Principal("user-42", PrincipalKind.HUMAN)
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val packs = listOf(PackVersionRef("mapping", "2026.3"), PackVersionRef("scope-rules", "2026.2"))

    private fun item(
        kind: ChainItemKind,
        reference: String,
        rank: Long,
        seconds: Long,
        packVersions: List<PackVersionRef> = emptyList(),
    ) = ChainItem(kind, at, reference, rank, ChainCursor(at.plusSeconds(seconds), kind, 0), packVersions)

    private val payload = item(ChainItemKind.PAYLOAD, "payload/$doc/v2/0", 1, 0)
    private val ack = item(ChainItemKind.ACKNOWLEDGEMENT, "A1", 2, 1)
    private val trace1 = item(ChainItemKind.DECISION_TRACE, "h1", 3, 2, packs)
    private val trace2 = item(ChainItemKind.DECISION_TRACE, "h2", 4, 3, packs.take(1))

    private inner class Locators : AcknowledgementLocatorStore {
        override fun insert(
            tenant: TenantId,
            eventId: String,
            ackId: String,
            docUuid: UUID,
            version: Int,
            recordedAt: Instant,
        ): Boolean = error("a lookup never writes")

        override fun find(
            tenant: TenantId,
            ackId: String,
        ): AcknowledgementLocator? = if (tenant == t1 && ackId == "A1") AcknowledgementLocator("A1", doc, 2) else null
    }

    private inner class Reader(
        private val answer: ChainRecords,
    ) : ChainRecordReader {
        override fun read(
            tenant: TenantId,
            ackId: String,
            docUuid: UUID,
            version: Int,
        ): ChainRecords = answer
    }

    private class Audit : AuditedOperations {
        val entries = mutableListOf<AuditEntry>()

        override fun <T> change(
            entry: AuditEntry,
            context: EventContext,
            block: () -> T,
        ): T = error("not used")

        override fun <T> read(
            entry: AuditEntry,
            context: EventContext,
            block: () -> T,
        ): T {
            entries += entry
            return block()
        }
    }

    private fun complete(vararg items: ChainItem) = ChainRecords.Complete(items.toList(), "ENT-1", "2026-Q1")

    private class Integrity(
        private val run: RunResult?,
    ) : IntegrityStore {
        val asked = mutableListOf<Pair<String, String>>()

        override fun lock(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ) = error("not used")

        override fun failureExists(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ): Boolean = error("not used")

        override fun insertFailure(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ) = error("not used")

        override fun insertRun(
            tenant: TenantId,
            row: IntegrityRunRow,
        ) = error("not used")

        override fun latestRun(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ): IntegrityRunRow? {
            asked += entityRef to period
            return run?.let {
                val runAt = Instant.parse("2026-11-01T00:00:00Z")
                IntegrityRunRow(entityRef, period, "R1", runAt, it, RunCause.VERIFICATION, 2, null)
            }
        }
    }

    private fun grant(
        tenant: TenantId,
        version: Long,
        scope: GrantScope,
        scopeRef: String?,
    ) = AuthorityChanged(
        tenant,
        user.id,
        version,
        AuthorityRecord.RoleGrantRecord(
            "g$version",
            RoleGrantState.ACTIVE,
            RoleGrant("auditor", scope, scopeRef ?: tenant.value.toString(), Instant.parse(SINCE), null),
        ),
    )

    private fun replica(
        key: String = ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT,
        current: Boolean = true,
        scope: GrantScope = GrantScope.TENANT,
        scopeRef: String? = null,
    ): AuthorityReplica {
        val roles = OperationRoleMap { if (it == key) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap() }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        listOf(t1, t2).forEachIndexed { i, tenant -> replica.accept(grant(tenant, i + 1L, scope, scopeRef)) }
        if (current) {
            replica.onSnapshotComplete(2)
            replica.observeHead(2)
        }
        return replica
    }

    private val context =
        EventContext(t1, EntityRef("ENT-1"), null, null, "agg", null, CorrelationId("c"), CausationId("d"))

    private fun lookup(records: ChainRecords) =
        AcknowledgementChainLookup(
            replica(),
            Locators(),
            Reader(records),
            IntegrityStatusQueryHandler(Integrity(null)),
            AuditedReads(Audit()),
        ).lookup(t1, user, "A1", 100, null, context)

    @Test
    fun `INV-AC-20779 the reader's DECISION_TRACE items are returned with PAYLOAD and ACKNOWLEDGEMENT in order`() {
        val found = lookup(complete(trace2, ack, trace1, payload)).shouldBeInstanceOf<ChainLookupResult.Found>()
        found.items shouldBe listOf(payload, ack, trace1, trace2)
        found.items.map { it.kind } shouldBe
            listOf(
                ChainItemKind.PAYLOAD,
                ChainItemKind.ACKNOWLEDGEMENT,
                ChainItemKind.DECISION_TRACE,
                ChainItemKind.DECISION_TRACE,
            )
        found.items.filter { it.kind == ChainItemKind.DECISION_TRACE }.map { it.packVersions } shouldBe
            listOf(packs, packs.take(1))
    }

    @Test
    fun `INV-AC-20779 an Incomplete from the reader is Incomplete for the use case`() {
        lookup(ChainRecords.Incomplete) shouldBe ChainLookupResult.Incomplete
    }

    @Test
    fun `INV-AC-20779 with no trace row the answer is Found with no DECISION_TRACE item`() {
        val found = lookup(complete(ack, payload)).shouldBeInstanceOf<ChainLookupResult.Found>()
        found.items shouldBe listOf(payload, ack)
        found.items.none { it.kind == ChainItemKind.DECISION_TRACE } shouldBe true
    }

    @Test
    fun `INV-AC-20779 the kind order puts DECISION_TRACE after ACKNOWLEDGEMENT at an equal instant`() {
        val k = { kind: ChainItemKind -> ChainCursor(at, kind, 0) }
        (k(ChainItemKind.PAYLOAD) < k(ChainItemKind.ACKNOWLEDGEMENT)) shouldBe true
        (k(ChainItemKind.ACKNOWLEDGEMENT) < k(ChainItemKind.DECISION_TRACE)) shouldBe true
    }

    private companion object {
        const val SINCE = "2026-01-01T00:00:00Z"
    }
}
