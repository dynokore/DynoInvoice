package com.dynokore.gst.evidence.application.lookup

import com.dynokore.gst.evidence.application.auditedread.AuditedReads
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class ReverseLookupTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val user = Principal("user-42", PrincipalKind.HUMAN)
    private val noKeyUser = Principal("user-43", PrincipalKind.HUMAN)
    private val calls = mutableListOf<String>()
    private val row =
        ReverseLookupRow(ReverseLookupKey(LocalDate.of(2026, 1, 15), doc, 1), "SENT", null, null, null, null)
    private val seen = mutableListOf<Triple<ReverseLookupFilter, ReverseLookupKey?, Int>>()

    private inner class Rows(
        private val rows: List<ReverseLookupRow>,
    ) : SubmissionIndexReader {
        override fun read(
            tenant: TenantId,
            filter: ReverseLookupFilter,
            after: ReverseLookupKey?,
            limit: Int,
        ): List<ReverseLookupRow> {
            calls += "read"
            seen += Triple(filter, after, limit)
            return rows.filter { after == null || it.key.version > after.version }.take(limit)
        }
    }

    private class Runs(
        private val runs: Map<Triple<TenantId, String, String>, RunResult>,
    ) : IntegrityStore {
        val asked = mutableListOf<Triple<TenantId, String, String>>()

        override fun lock(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ) = Unit

        override fun failureExists(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ) = false

        override fun insertFailure(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ) = Unit

        override fun insertRun(
            tenant: TenantId,
            row: IntegrityRunRow,
        ) = Unit

        override fun latestRun(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ): IntegrityRunRow? {
            asked += Triple(tenant, entityRef, period)
            val at = Instant.parse("2026-11-01T00:00:00Z")
            return runs[Triple(tenant, entityRef, period)]?.let {
                IntegrityRunRow(entityRef, period, "r", at, it, RunCause.VERIFICATION, 1, null)
            }
        }
    }

    private var store = Runs(emptyMap())

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
        key: String = ENQUIRY_LOOKUP_REVERSE,
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

    @Suppress("LongParameterList") // every input of one lookup, each defaulted to the happy case

    private fun lookup(
        who: Principal = user,
        replica: AuthorityReplica = replica(),
        rows: List<ReverseLookupRow> = listOf(row),
        filter: ReverseLookupFilter = ReverseLookupFilter(),
        limit: Int = 100,
        cursor: String? = null,
    ) = ReverseLookup(replica, Rows(rows), IntegrityStatusQueryHandler(store), AuditedReads(audit))
        .lookup(t1, who, filter, limit, cursor, context)

    @Test
    fun `INV-AC-20722 a caller with the key gets the page with state SENT and no ack`() {
        val page = lookup().shouldBeInstanceOf<ReverseLookupResult.Page>()
        page.items shouldBe listOf(row)
        page.nextCursor shouldBe null
        calls shouldBe listOf("read")
    }

    @Test
    fun `INV-AC-20722 a bad cursor is InvalidRequest and no store is read`() {
        val padded = Base64.getUrlEncoder().encodeToString("2026-01-15|$doc|1".toByteArray())
        listOf("!!!", "", "bm90LWEtY3Vyc29y", padded)
            .forEach { lookup(cursor = it) shouldBe ReverseLookupResult.InvalidRequest }
        calls shouldBe emptyList()
        audit.entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20722 limit 0 and 501 are InvalidRequest and no store is read`() {
        lookup(limit = 0) shouldBe ReverseLookupResult.InvalidRequest
        lookup(limit = 501) shouldBe ReverseLookupResult.InvalidRequest
        calls shouldBe emptyList()
        lookup(limit = 1).shouldBeInstanceOf<ReverseLookupResult.Page>()
        lookup(limit = 500).shouldBeInstanceOf<ReverseLookupResult.Page>()
    }

    @Test
    fun `INV-AC-20722 categoryCode and counterparties of 8 and 11 characters and with a space are never refused`() {
        listOf("201912345", "201912345AB", "A B").forEach { c ->
            lookup(filter = ReverseLookupFilter(counterparty = c)).shouldBeInstanceOf<ReverseLookupResult.Page>()
            seen.last().first.counterparty shouldBe c
        }
        lookup(filter = ReverseLookupFilter(categoryCode = "ZZ")).shouldBeInstanceOf<ReverseLookupResult.Page>()
        seen.last().first.categoryCode shouldBe "ZZ"
        calls.size shouldBe 4
    }

    @Test
    fun `INV-AC-20722 a caller without the key is Forbidden and no store is read`() {
        lookup(who = noKeyUser) shouldBe ReverseLookupResult.Forbidden
        calls shouldBe emptyList()
        audit.entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20722 AUTHORITY_UNAVAILABLE is Forbidden, fail closed, and no store is read`() {
        lookup(replica = replica(current = false)) shouldBe ReverseLookupResult.Forbidden
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20722 the look-alike key enquiry-lookup-by-acknowledgement does not allow the lookup`() {
        lookup(replica = replica(key = "enquiry-lookup.by-acknowledgement")) shouldBe ReverseLookupResult.Forbidden
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20722 the audit port receives the acting user id and no role string`() {
        lookup(filter = ReverseLookupFilter(counterparty = "201912345A"))
        val entry = audit.entries.single()
        entry.actor shouldBe "user-42"
        listOf(entry.actor, entry.action, entry.scope, entry.reason).forEach { it shouldNotBe "auditor" }
        entry.scope.contains("auditor") shouldBe false
        entry.scope.contains("counterparty=") shouldBe true
    }

    @Test
    fun `INV-AC-20722 the page follows limit and the cursor returns every row once`() {
        val rows = (1..5).map { row.copy(key = row.key.copy(version = it)) }
        val first = lookup(rows = rows, limit = 2).shouldBeInstanceOf<ReverseLookupResult.Page>()
        first.items shouldBe rows.take(2)
        first.nextCursor shouldBe ReverseLookup.encode(rows[1].key)
        val second =
            lookup(rows = rows, limit = 2, cursor = first.nextCursor)
                .shouldBeInstanceOf<ReverseLookupResult.Page>()
        second.items shouldBe rows.subList(2, 4)
        val third =
            lookup(rows = rows, limit = 2, cursor = second.nextCursor)
                .shouldBeInstanceOf<ReverseLookupResult.Page>()
        third.items shouldBe rows.drop(4)
        third.nextCursor shouldBe null
    }

    private fun pairRow(
        version: Int,
        entity: String?,
        period: String?,
    ) = row.copy(key = row.key.copy(version = version), entityRef = entity, periodRef = period)

    private fun status(
        runs: Map<Triple<TenantId, String, String>, RunResult>,
        rows: List<ReverseLookupRow>,
        limit: Int = 100,
    ): IntegrityState {
        store = Runs(runs)
        return lookup(rows = rows, limit = limit).shouldBeInstanceOf<ReverseLookupResult.Page>().integrityStatus
    }

    private fun run(
        tenant: TenantId,
        entity: String,
        period: String,
        result: RunResult,
    ) = Triple(tenant, entity, period) to result

    private val v1 = run(t1, "E1", "P1", RunResult.VERIFIED)
    private val v2 = run(t1, "E2", "P1", RunResult.VERIFIED)
    private val f2 = run(t1, "E2", "P1", RunResult.FAILED)
    private val r1 = pairRow(1, "E1", "P1")
    private val r2 = pairRow(2, "E2", "P1")

    @Test
    fun `INV-AC-21950 all pairs VERIFIED gives VERIFIED`() {
        status(mapOf(v1, v2), listOf(r1, r2)) shouldBe IntegrityState.VERIFIED
    }

    @Test
    fun `INV-AC-21950 one FAILED and one VERIFIED gives FAILED`() {
        status(mapOf(v1, f2), listOf(r1, r2)) shouldBe IntegrityState.FAILED
    }

    @Test
    fun `INV-AC-21950 one FAILED and one with no run gives FAILED`() {
        status(mapOf(f2), listOf(r1, r2)) shouldBe IntegrityState.FAILED
    }

    @Test
    fun `INV-AC-21950 one with no run and one VERIFIED gives NEVER_RUN`() {
        status(mapOf(v1), listOf(r1, r2)) shouldBe IntegrityState.NEVER_RUN
    }

    @Test
    fun `INV-AC-21950 an empty page gives NEVER_RUN`() {
        status(mapOf(v1), emptyList()) shouldBe IntegrityState.NEVER_RUN
    }

    @Test
    fun `INV-AC-21950 a row with a null entityRef or periodRef among VERIFIED rows gives NEVER_RUN`() {
        status(mapOf(v1, v2), listOf(r1, r2, pairRow(3, null, "P1"))) shouldBe IntegrityState.NEVER_RUN
        status(mapOf(v1, v2), listOf(r1, r2, pairRow(3, "E1", null))) shouldBe IntegrityState.NEVER_RUN
    }

    @Test
    fun `INV-AC-21950 three rows of one pair ask the store once`() {
        status(mapOf(v1), listOf(r1, pairRow(2, "E1", "P1"), pairRow(3, "E1", "P1"))) shouldBe IntegrityState.VERIFIED
        store.asked shouldBe listOf(Triple(t1, "E1", "P1"))
    }

    @Test
    fun `INV-AC-21950 the extra row read for the cursor is never asked`() {
        val r3 = pairRow(3, "E3", "P1")
        status(mapOf(v1, v2, run(t1, "E3", "P1", RunResult.FAILED)), listOf(r1, r2, r3), limit = 2) shouldBe
            IntegrityState.VERIFIED
        store.asked.map { it.second } shouldBe listOf("E1", "E2")
    }

    @Test
    fun `INV-AC-21950 a FAILED run of another period or of another tenant leaves the page VERIFIED`() {
        val other = mapOf(v1, run(t1, "E1", "P2", RunResult.FAILED), run(t2, "E1", "P1", RunResult.FAILED))
        status(other, listOf(r1)) shouldBe IntegrityState.VERIFIED
    }

    @Test
    fun `INV-AC-21950 Forbidden and InvalidRequest ask the integrity store nothing`() {
        store = Runs(mapOf(v1))
        lookup(who = noKeyUser, rows = listOf(r1)) shouldBe ReverseLookupResult.Forbidden
        lookup(limit = 0, rows = listOf(r1)) shouldBe ReverseLookupResult.InvalidRequest
        lookup(cursor = "!!!", rows = listOf(r1)) shouldBe ReverseLookupResult.InvalidRequest
        store.asked shouldBe emptyList()
    }

    private companion object {
        const val SINCE = "2026-01-01T00:00:00Z"
    }
}
