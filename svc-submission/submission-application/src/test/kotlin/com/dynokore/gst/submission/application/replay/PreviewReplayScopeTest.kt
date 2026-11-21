package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PreviewReplayScopeTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A replica that grants [holder] the roles the catalogue lists for [key], on entity e1. */
    private fun replica(key: String = ReplayActions.REPLAY) =
        AuthorityReplica(clock, roleMap).also {
            it.onSnapshotComplete(0)
            val role =
                roleMap
                    .rolesFor(key)
                    .keys
                    .sorted()
                    .first()
            val grant = RoleGrant(role, GrantScope.ENTITY, "e1", clock.instant().minusSeconds(60))
            it.accept(
                AuthorityChanged(
                    tenant,
                    holder.id,
                    1,
                    AuthorityRecord.RoleGrantRecord("$role-e1", RoleGrantState.ACTIVE, grant),
                ),
            )
        }

    private fun candidate(
        doc: String,
        version: Int = 1,
        period: String = "2026-Q1",
        state: LifecycleState = LifecycleState.DEAD_LETTERED,
        failureClass: String = "F1",
    ) = ReplayCandidate(
        doc,
        version,
        "e1",
        period,
        state,
        failureClass,
        LocalDate.of(2026, 11, 30),
        Instant.parse("2026-10-01T00:00:00Z").plusSeconds(version.toLong()),
    )

    private class FakeSource(
        private val all: List<ReplayCandidate>,
    ) : ReplayCandidateSource {
        var calls = 0

        override fun candidates(
            tenant: TenantId,
            entityRef: String,
        ): List<ReplayCandidate> {
            calls++
            return all
        }
    }

    private class FakeProbe(
        private val inFlight: Set<String> = emptySet(),
    ) : ReplayInFlightProbe {
        var calls = 0

        override fun hasOtherInFlight(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ): Boolean {
            calls++
            return docUuid in inFlight
        }
    }

    private class FakeFacts(
        private val amounts: Map<String, String?>,
        private val default: String? = null,
    ) : RegisterListFactsStore {
        var calls = 0

        override fun store(
            tenant: TenantId,
            docUuid: String,
            facts: ListFacts,
        ): Boolean = error("the preview never stores")

        override fun facts(
            tenant: TenantId,
            docUuid: String,
        ): ListFacts? {
            calls++
            val amount = (if (docUuid in amounts) amounts[docUuid] else default) ?: return null
            // taxable plus GST: the GST part is a fixed 10 percent share, so the sum is the stated amount
            val total = BigDecimal(amount)
            val gst = total.divide(BigDecimal("11"), 2, java.math.RoundingMode.HALF_UP)
            return ListFacts("N", "380", Direction.SALE, "C", "201912345A", total.subtract(gst), gst, emptySet())
        }
    }

    /** The test fixture requeue: calling it fails the test. */
    private val failingRequeue =
        ReplayRequeue { _, _, _ -> error("the preview must not requeue") }

    private val scope = ReplayScope("e1", "2026-Q1", null, null)

    private inner class Ports(
        candidates: List<ReplayCandidate>,
        amounts: Map<String, String?> = emptyMap(),
        default: String? = null,
        inFlight: Set<String> = emptySet(),
    ) {
        val source = FakeSource(candidates)
        val probe = FakeProbe(inFlight)
        val facts = FakeFacts(amounts, default)
    }

    private fun run(
        ports: Ports,
        replica: AuthorityReplica = replica(),
        who: Principal = holder,
        scope: ReplayScope = this.scope,
    ) = PreviewReplayScope(replica, ports.source, ports.probe, ports.facts)
        .run(PreviewReplayScopeRequest(tenant, who, scope), deadline)

    private val two = listOf(candidate("doc-a"), candidate("doc-b", version = 2))
    private val twoAmounts = mapOf("doc-a" to "40.00", "doc-b" to "60.00")

    private fun PreviewReplayScopeResult.previewed() = shouldBeInstanceOf<PreviewReplayScopeResult.Previewed>()

    private fun BigDecimal.shouldEqualScale2(expected: String) {
        compareTo(BigDecimal(expected)) shouldBe 0
        scale() shouldBe 2
    }

    @Test
    fun `INV-AC-25827 previews the dead-lettered versions of the scope with count and total`() {
        val p = run(Ports(two, twoAmounts)).previewed()
        p.entityRef shouldBe "e1"
        p.documentCount shouldBe 2
        p.totalAmount.shouldEqualScale2("100.00")
    }

    @Test
    fun `INV-AC-25827 a version in another state is not counted`() {
        val pool =
            two +
                candidate("doc-c", state = LifecycleState.SENT_UNCONFIRMED) +
                candidate("doc-d", state = LifecycleState.QUEUED)
        val p = run(Ports(pool, twoAmounts + ("doc-c" to "25.00") + ("doc-d" to "15.00"))).previewed()
        p.documentCount shouldBe 2
        p.totalAmount.shouldEqualScale2("100.00")
    }

    @Test
    fun `INV-AC-25827 a version with another in-flight version of its Doc UUID is not counted`() {
        val p = run(Ports(two, twoAmounts, inFlight = setOf("doc-a"))).previewed()
        p.documentCount shouldBe 1
        p.totalAmount.shouldEqualScale2("60.00")
    }

    @Test
    fun `INV-AC-25827 the count is not capped at 500`() {
        val many = (1..501).map { candidate("doc-$it") }
        val p = run(Ports(many, default = "1.00")).previewed()
        p.documentCount shouldBe 501
        p.totalAmount.shouldEqualScale2("501.00")
    }

    @Test
    fun `INV-AC-25827 the total is exact decimal arithmetic at scale 2`() {
        val tenths = (1..10).map { candidate("doc-$it") }
        val p = run(Ports(tenths, default = "0.10")).previewed()
        p.documentCount shouldBe 10
        p.totalAmount.shouldEqualScale2("1.00")
    }

    @Test
    fun `INV-AC-25827 the scope narrows by period, document and failure class`() {
        val pool = listOf(candidate("doc-a"), candidate("doc-b", version = 2, failureClass = "F2"))
        val amounts = mapOf("doc-a" to "1.00", "doc-b" to "1.00")
        run(Ports(pool, amounts), scope = ReplayScope("e1", "2026-Q2", null, null)).previewed().documentCount shouldBe 0
        run(Ports(pool, amounts), scope = ReplayScope("e1", null, null, "F2")).previewed().documentCount shouldBe 1
        run(Ports(pool, amounts), scope = ReplayScope("e1", null, "doc-a", null)).previewed().documentCount shouldBe 1
    }

    @Test
    fun `INV-AC-25827 a denied replica answer gives Forbidden and reads nothing`() {
        val ports = Ports(two, twoAmounts)
        run(ports, who = stranger) shouldBe PreviewReplayScopeResult.Forbidden(DecisionReason.NO_GRANT)
        ports.source.calls shouldBe 0
        ports.probe.calls shouldBe 0
        ports.facts.calls shouldBe 0
    }

    @Test
    fun `INV-AC-25827 a replica allowing only a look-alike key gives Forbidden`() {
        // the catalogue keys differ from submission.replay; the key is compared by equality
        val other = roleMap.rolesFor("submission.view-status").keys - roleMap.rolesFor(ReplayActions.REPLAY).keys
        other.isNotEmpty() shouldBe true
        val replica =
            AuthorityReplica(clock, roleMap).also {
                it.onSnapshotComplete(0)
                val grant = RoleGrant(other.sorted().first(), GrantScope.ENTITY, "e1", clock.instant().minusSeconds(60))
                it.accept(
                    AuthorityChanged(
                        tenant,
                        holder.id,
                        1,
                        AuthorityRecord.RoleGrantRecord("x-e1", RoleGrantState.ACTIVE, grant),
                    ),
                )
            }
        run(Ports(two, twoAmounts), replica = replica).shouldBeInstanceOf<PreviewReplayScopeResult.Forbidden>()
    }

    @Test
    fun `INV-AC-25827 an unavailable authority gives Forbidden`() {
        val ports = Ports(two, twoAmounts)
        run(ports, replica = AuthorityReplica(clock, roleMap)) shouldBe
            PreviewReplayScopeResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        ports.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-25827 a missing amount gives Unavailable with no total`() {
        run(
            Ports(two, mapOf("doc-a" to "40.00", "doc-b" to null)),
        ).shouldBeInstanceOf<PreviewReplayScopeResult.Unavailable>()
    }

    @Test
    fun `INV-AC-25827 the preview changes no state`() {
        // the use case takes no ReplayRequeue: its four ports compile without one, and the fixture requeue is unused
        val useCase = PreviewReplayScope(replica(), FakeSource(two), FakeProbe(), FakeFacts(twoAmounts))
        useCase.run(PreviewReplayScopeRequest(tenant, holder, scope), deadline).previewed().documentCount shouldBe 2
        failingRequeue.shouldBeInstanceOf<ReplayRequeue>()
    }
}
