package com.dynokore.gst.evidence.application.lookup

import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocator
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
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

class AcknowledgementChainLookupTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val user = Principal("user-42", PrincipalKind.HUMAN)
    private val noKeyUser = Principal("user-43", PrincipalKind.HUMAN)
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val calls = mutableListOf<String>()

    private val payload = ChainItem(ChainItemKind.PAYLOAD, at, "payload/$doc/v2/1", 1)
    private val ack = ChainItem(ChainItemKind.ACKNOWLEDGEMENT, at, "A1", 2)

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
        ): AcknowledgementLocator? {
            calls += "find"
            return if (tenant == t1 && ackId == "A1") AcknowledgementLocator("A1", doc, 2) else null
        }
    }

    private inner class Reader(
        private val answer: ChainRecords,
    ) : ChainRecordReader {
        override fun read(
            tenant: TenantId,
            ackId: String,
            docUuid: UUID,
            version: Int,
        ): ChainRecords {
            calls += "read"
            return answer
        }
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

    private val audit = Audit()
    private val context =
        EventContext(t1, EntityRef("ENT-1"), null, null, "agg", null, CorrelationId("c"), CausationId("d"))

    private fun grant(
        tenant: TenantId,
        version: Long,
    ) = AuthorityChanged(
        tenant,
        user.id,
        version,
        AuthorityRecord.RoleGrantRecord(
            "g$version",
            RoleGrantState.ACTIVE,
            RoleGrant("auditor", GrantScope.TENANT, tenant.value.toString(), Instant.parse(SINCE), null),
        ),
    )

    private fun replica(
        key: String = ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT,
        current: Boolean = true,
    ): AuthorityReplica {
        val roles = OperationRoleMap { if (it == key) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap() }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        listOf(t1, t2).forEachIndexed { i, tenant -> replica.accept(grant(tenant, i + 1L)) }
        if (current) {
            replica.onSnapshotComplete(2)
            replica.observeHead(2)
        }
        return replica
    }

    @Suppress("LongParameterList") // every input of one lookup, each defaulted to the happy case
    private fun lookup(
        tenant: TenantId = t1,
        ackId: String = "A1",
        who: Principal = user,
        replica: AuthorityReplica = replica(),
        records: ChainRecords = ChainRecords.Complete(listOf(ack, payload)),
        limit: Int = 100,
        cursor: ChainCursor? = null,
    ) = AcknowledgementChainLookup(replica, Locators(), Reader(records), AuditedReads(audit))
        .lookup(tenant, who, ackId, limit, cursor, context)

    @Test
    fun `INV-AC-20574 found answers PAYLOAD then ACKNOWLEDGEMENT in sequence order and no DECISION_TRACE item`() {
        val found = lookup().shouldBeInstanceOf<ChainLookupResult.Found>()
        found.items shouldBe listOf(payload, ack)
        found.items.map { it.kind.name } shouldBe listOf("PAYLOAD", "ACKNOWLEDGEMENT")
        found.docUuid shouldBe doc
        found.version shouldBe 2
        found.nextCursor shouldBe null
        calls shouldBe listOf("find", "read")
    }

    @Test
    fun `INV-AC-20574 the page follows limit and cursor`() {
        val first = lookup(limit = 1).shouldBeInstanceOf<ChainLookupResult.Found>()
        first.items shouldBe listOf(payload)
        first.nextCursor shouldBe ChainCursor(1, ChainItemKind.PAYLOAD)
        val second = lookup(limit = 1, cursor = first.nextCursor).shouldBeInstanceOf<ChainLookupResult.Found>()
        second.items shouldBe listOf(ack)
        second.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-20574 another tenant's id and an unknown id give the identical NotFound`() {
        val foreign = lookup(tenant = t2)
        val unknown = lookup(ackId = "NOPE")
        foreign shouldBe ChainLookupResult.NotFound
        unknown shouldBe foreign
    }

    @Test
    fun `INV-AC-20574 a caller without the key is Forbidden and no store is read`() {
        lookup(who = noKeyUser) shouldBe ChainLookupResult.Forbidden
        calls shouldBe emptyList()
        audit.entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20574 a locator whose record is missing is Incomplete`() {
        lookup(records = ChainRecords.Incomplete) shouldBe ChainLookupResult.Incomplete
    }

    @Test
    fun `INV-AC-20574 AUTHORITY_UNAVAILABLE is Forbidden, fail closed`() {
        lookup(replica = replica(current = false)) shouldBe ChainLookupResult.Forbidden
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20574 the audited read receives the acting user id and no role string`() {
        lookup()
        val entry = audit.entries.single()
        entry.actor shouldBe "user-42"
        listOf(entry.actor, entry.action, entry.scope, entry.reason).forEach { it shouldNotBe "auditor" }
        entry.scope.contains("auditor") shouldBe false
    }

    @Test
    fun `INV-AC-20574 the look-alike key enquiry-lookup-reverse does not allow the lookup`() {
        lookup(replica = replica(key = "enquiry-lookup.reverse")) shouldBe ChainLookupResult.Forbidden
        calls shouldBe emptyList()
    }

    private companion object {
        const val SINCE = "2026-01-01T00:00:00Z"
    }
}
