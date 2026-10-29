package com.dynokore.gst.submission.application.compliance

import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.metrics.SignalState
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ComplianceMetricsQueryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 30)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenantA = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)
    private val config = ComplianceMetricsConfig(manyVersionsFrom = 3, highAtOrAbove = Ratio(1, 2), trendPeriods = 3)

    private class CountingSource(
        private val byTenant: Map<TenantId, List<ObligationRow>>,
    ) : ObligationRowSource {
        var calls = 0
        var last: Triple<TenantId, String?, String?>? = null

        override fun rows(
            tenant: TenantId,
            entityRef: String?,
            periodRef: String?,
        ): List<ObligationRow> {
            calls++
            last = Triple(tenant, entityRef, periodRef)
            return byTenant[tenant].orEmpty()
        }
    }

    private fun row(
        id: String,
        entity: String = "e1",
        period: String = "2026-Q1",
        submitted: Boolean = false,
        versions: Int = 1,
    ) = ObligationRow(id, entity, period, today.plusDays(3), true, submitted, RowKind.INVOICE, versions)

    private fun replica(
        principal: Principal,
        action: String,
        scope: ScopeLevel,
        entityRef: String = "e1",
    ) = AuthorityReplica(clock).also {
        it.onSnapshotComplete(0)
        val grant = Grant(action, scope, "x", clock.instant().minusSeconds(60))
        it.accept(AuthorityChanged(tenantA, principal.id, 1, AuthorityRecord.GrantRecord(entityRef, listOf(grant))))
    }

    private fun granted(principal: Principal = holder) = replica(principal, ComplianceActions.VIEW, ScopeLevel.TENANT)

    private fun query(
        source: ObligationRowSource,
        replica: AuthorityReplica,
    ) = ComplianceMetricsQuery(replica, source, config)

    private fun ask(
        q: ComplianceMetricsQuery,
        who: Principal = holder,
        entity: String? = null,
        period: String? = null,
        tenant: TenantId = tenantA,
    ) = q.handle(ComplianceMetricsRequest(tenant, who, entity, period), today, deadline)

    @Test
    fun `INV-AC-12835 the constant and the set of constants of ComplianceActions`() {
        ComplianceActions.VIEW shouldBe "compliance-metrics.view"
        ComplianceActions::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.name != "INSTANCE"
            }.map { it.name } shouldBe listOf("VIEW")
    }

    @Test
    fun `INV-AC-12835 a holder reads ten entities and a principal without a grant is refused with no row read`() {
        val rows = (1..10).map { row("d$it", entity = "e$it", submitted = it != 1) }
        val source = CountingSource(mapOf(tenantA to rows))
        val ok = ask(query(source, granted())).shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        ok.entries.size shouldBe 10
        ok.entries
            .first()
            .metrics.coverage.documentsWithoutSubmission shouldBe 1
        ok.entries
            .first()
            .metrics.coverage.breached shouldBe true
        source.calls shouldBe 1

        val refusedSource = CountingSource(mapOf(tenantA to rows))
        ask(query(refusedSource, granted()), who = stranger) shouldBe
            ComplianceMetricsResult.Forbidden(DecisionReason.NO_GRANT)
        refusedSource.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 a grant of another action refuses and rows is never called`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        val other = replica(holder, "other.action", ScopeLevel.TENANT)
        ask(query(source, other)).shouldBeInstanceOf<ComplianceMetricsResult.Forbidden>()
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 an entity filtered read is decided on that entity and the filters reach rows unchanged`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        val q = query(source, replica(holder, ComplianceActions.VIEW, ScopeLevel.ENTITY))
        ask(q, entity = "e1", period = "2026-Q1").shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        source.last shouldBe Triple(tenantA, "e1", "2026-Q1")
        ask(q, entity = "e2").shouldBeInstanceOf<ComplianceMetricsResult.Forbidden>()
        source.calls shouldBe 1
    }

    @Test
    fun `INV-AC-12835 an absent filter reaches rows as null`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        ask(query(source, granted()))
        source.last shouldBe Triple(tenantA, null, null)
    }

    @Test
    fun `INV-AC-12835 REVOKED refuses and rows is never called`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        val r = granted()
        r.accept(AuthorityChanged(tenantA, holder.id, 2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        ask(query(source, r)) shouldBe ComplianceMetricsResult.Forbidden(DecisionReason.REVOKED)
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 AUTHORITY_UNAVAILABLE refuses fail closed and rows is never called`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        val cold = AuthorityReplica(clock)
        ask(query(source, cold)) shouldBe ComplianceMetricsResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        ask(query(source, cold), entity = "e1") shouldBe
            ComplianceMetricsResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12849 four periods give the trend cut to trendPeriods and a RISING signal in period order`() {
        val ratios = listOf(1 to 10, 2 to 10, 3 to 10, 4 to 10)
        // rows given out of period order; the use case orders by periodRef
        val rows =
            ratios.mapIndexed { i, (v, n) -> i to (v to n) }.reversed().flatMap { (i, vn) ->
                (1..vn.second).map { k ->
                    row(
                        "p$i-$k",
                        period = "2026-Q${i + 1}",
                        versions =
                            if (k <=
                                vn.first
                            ) {
                                2
                            } else {
                                1
                            },
                    )
                }
            }
        val source = CountingSource(mapOf(tenantA to rows))
        val ok = ask(query(source, granted()), entity = null).shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        ok.entries.map { it.metrics.periodRef } shouldBe listOf("2026-Q1", "2026-Q2", "2026-Q3", "2026-Q4")
        ok.entries.forEach {
            it.resubmissionTrend shouldBe listOf(Ratio(2, 10), Ratio(3, 10), Ratio(4, 10))
            it.resubmissionSignal shouldBe SignalState.RISING
        }
    }

    @Test
    fun `INV-AC-12849 a falling series gives NONE`() {
        val rows =
            listOf(4, 3).flatMapIndexed { i, v ->
                (1..10).map { k -> row("p$i-$k", period = "2026-Q${i + 1}", versions = if (k <= v) 2 else 1) }
            }
        val ok =
            ask(
                query(CountingSource(mapOf(tenantA to rows)), granted()),
            ).shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        ok.entries.map { it.resubmissionSignal }.distinct() shouldBe listOf(SignalState.NONE)
    }

    @Test
    fun `INV-AC-12849 a trend is per entity and never mixes entities`() {
        val rows = listOf(row("a", entity = "e1", versions = 2), row("b", entity = "e2"))
        val ok =
            ask(
                query(CountingSource(mapOf(tenantA to rows)), granted()),
            ).shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        ok.entries.map { it.resubmissionTrend } shouldBe listOf(listOf(Ratio(1, 1)), listOf(Ratio(0, 1)))
    }

    @Test
    fun `INV-AC-12835 the pairs of a second tenant never appear`() {
        val source =
            CountingSource(
                mapOf(
                    tenantA to listOf(row("a1", entity = "mine")),
                    tenantB to listOf(row("b1", entity = "theirs")),
                ),
            )
        val ok = ask(query(source, granted())).shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        ok.entries.map { it.metrics.entityRef } shouldBe listOf("mine")
        source.last?.first shouldBe tenantA
    }

    private fun machine(): Principal =
        // a machine principal exists only from a verified token (service-runtime); the test builds the same shape
        Principal::class.java.declaredConstructors
            .single { it.parameterCount == 4 && !it.isSynthetic }
            .apply { isAccessible = true }
            .newInstance("machine", PrincipalKind.MACHINE, null, MachineEntityCoverage.None) as Principal

    @Test
    fun `INV-AC-12835 with no entity filter a TENANT grant gives Allowed and rows receives a null entityRef`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        ask(query(source, granted()), period = "2026-Q1").shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        source.last shouldBe Triple(tenantA, null, "2026-Q1")
    }

    @Test
    fun `INV-AC-12835 with no entity filter an ENTITY grant alone is refused and rows is never called`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        val q = query(source, replica(holder, ComplianceActions.VIEW, ScopeLevel.ENTITY))
        ask(q) shouldBe ComplianceMetricsResult.Forbidden(DecisionReason.NO_GRANT)
        ask(q, period = "2026-Q1") shouldBe ComplianceMetricsResult.Forbidden(DecisionReason.NO_GRANT)
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 with no entity filter a TENANT grant of another action is refused`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        ask(query(source, replica(holder, "other.action", ScopeLevel.TENANT))) shouldBe
            ComplianceMetricsResult.Forbidden(DecisionReason.NO_GRANT)
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 with no entity filter a lag beyond the bound refuses a HUMAN and a MACHINE principal`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        val cold = AuthorityReplica(clock)
        listOf(holder, machine()).forEach {
            ask(query(source, cold), who = it) shouldBe
                ComplianceMetricsResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        }
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 with an entity filter e1 a grant on e1 gives Allowed and a grant on e2 only is refused`() {
        val source = CountingSource(mapOf(tenantA to listOf(row("d1"))))
        ask(query(source, replica(holder, ComplianceActions.VIEW, ScopeLevel.ENTITY, "e1")), entity = "e1")
            .shouldBeInstanceOf<ComplianceMetricsResult.Allowed>()
        ask(query(source, replica(holder, ComplianceActions.VIEW, ScopeLevel.ENTITY, "e2")), entity = "e1") shouldBe
            ComplianceMetricsResult.Forbidden(DecisionReason.NO_GRANT)
        source.calls shouldBe 1
    }
}
