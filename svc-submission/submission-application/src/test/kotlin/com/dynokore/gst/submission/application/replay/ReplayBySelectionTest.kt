package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ReplayBySelectionTest {
    private companion object {
        /** A key of the catalogue whose roles differ from those of the replay action. */
        const val OTHER_KEY = "submission.view-status"
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)

    private fun candidate(
        doc: String,
        version: Int = 1,
        entity: String = "e1",
        period: String = "2026-Q1",
        state: LifecycleState = LifecycleState.DEAD_LETTERED,
    ) = ReplayCandidate(
        doc,
        version,
        entity,
        period,
        state,
        "F1",
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

    private class FakeRequeue(
        private val conflicts: Set<String> = emptySet(),
    ) : ReplayRequeue {
        val calls = mutableListOf<Triple<TenantId, String, Int>>()
        private val queued = HashSet<Pair<String, Int>>()

        override fun requeue(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ): RequeueOutcome {
            calls.add(Triple(tenant, docUuid, version))
            return when {
                docUuid in conflicts -> RequeueOutcome.IN_FLIGHT_CONFLICT
                queued.add(docUuid to version) -> RequeueOutcome.REQUEUED
                else -> RequeueOutcome.ALREADY_QUEUED
            }
        }
    }

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A role the catalogue lists for [key], read from the map; no role name is written in this test. */
    private fun roleOf(key: String): String = roleMap.rolesFor(key).keys.sorted().first()

    /** A role the catalogue lists for [key] and does not list for [notFor]. */
    private fun roleOfOnly(
        key: String,
        notFor: String,
    ): String = (roleMap.rolesFor(key).keys - roleMap.rolesFor(notFor).keys).sorted().first()

    private fun replica(
        principal: Principal = holder,
        action: String = ReplayActions.REPLAY,
    ) = AuthorityReplica(clock, roleMap).also {
        it.onSnapshotComplete(0)
        val role = if (action == "other.action") roleOfOnly(OTHER_KEY, ReplayActions.REPLAY) else roleOf(action)
        val grant = RoleGrant(role, GrantScope.ENTITY, "e1", clock.instant().minusSeconds(60))
        val record = AuthorityRecord.RoleGrantRecord("$role-e1", RoleGrantState.ACTIVE, grant)
        it.accept(AuthorityChanged(tenant, principal.id, 1, record))
    }

    private val scope = ReplayScope("e1", "2026-Q1", null, null)

    private fun run(
        replica: AuthorityReplica,
        source: ReplayCandidateSource,
        requeue: ReplayRequeue,
        who: Principal = holder,
    ) = ReplayBySelection(replica, source, requeue)
        .handle(ReplayBySelectionRequest(tenant, who, scope, 10), deadline)

    private val a = candidate("doc-a")
    private val b = candidate("doc-b", version = 2)
    private val pool =
        listOf(
            a,
            b,
            candidate("doc-c", state = LifecycleState.SENT_UNCONFIRMED),
            candidate("doc-d", period = "2026-Q2"),
            candidate("doc-e", entity = "e2"),
        )

    @Test
    fun `INV-AC-13133 an allowed caller replays the two matching versions and requeue gets only their keys`() {
        val requeue = FakeRequeue()
        val done = run(replica(), FakeSource(pool), requeue).shouldBeInstanceOf<ReplayBySelectionResult.Done>()
        done.replayed shouldBe listOf(a, b)
        done.skipped shouldBe emptyList()
        done.matchedTruncated shouldBe false
        requeue.calls shouldBe listOf(Triple(tenant, "doc-a", 1), Triple(tenant, "doc-b", 2))
    }

    @Test
    fun `INV-AC-13133 the same request repeated is ALREADY_QUEUED and gives the same replayed list`() {
        val requeue = FakeRequeue()
        val r = replica()
        val first = run(r, FakeSource(pool), requeue)
        val second = run(r, FakeSource(pool), requeue)
        second shouldBe first
        requeue.calls.size shouldBe 4
    }

    @Test
    fun `INV-AC-13133 a refused caller gets Forbidden with 0 source calls and 0 requeue calls`() {
        val source = FakeSource(pool)
        val requeue = FakeRequeue()
        run(replica(), source, requeue, who = stranger) shouldBe
            ReplayBySelectionResult.Forbidden(DecisionReason.NO_GRANT)
        run(replica(action = "other.action"), source, requeue).shouldBeInstanceOf<ReplayBySelectionResult.Forbidden>()
        source.calls shouldBe 0
        requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-13133 AUTHORITY_UNAVAILABLE is Forbidden with 0 source calls and 0 requeue calls`() {
        val source = FakeSource(pool)
        val requeue = FakeRequeue()
        run(AuthorityReplica(clock, roleMap), source, requeue) shouldBe
            ReplayBySelectionResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        source.calls shouldBe 0
        requeue.calls shouldBe emptyList()
    }

    @Test
    fun `the permission key and the entity reach the replica unchanged`() {
        ReplayActions.REPLAY shouldBe "submission.replay"
        // a grant of exactly the string on entity e1 allows; the same grant on e2 does not
        run(replica(), FakeSource(pool), FakeRequeue()).shouldBeInstanceOf<ReplayBySelectionResult.Done>()
        val other = ReplayBySelection(replica(), FakeSource(pool), FakeRequeue())
        other
            .handle(
                ReplayBySelectionRequest(tenant, holder, ReplayScope("e2", null, null, null), 10),
                deadline,
            ) shouldBe
            ReplayBySelectionResult.Forbidden(DecisionReason.NO_GRANT)
        replica().decide(AuthorizationRequest(tenant, holder, "e1", "submission.replay"), deadline).allowed shouldBe
            true
    }

    @Test
    fun `an IN_FLIGHT_CONFLICT for the first version leaves the second replayed and lists the first as skipped`() {
        val requeue = FakeRequeue(conflicts = setOf("doc-a"))
        val done = run(replica(), FakeSource(pool), requeue).shouldBeInstanceOf<ReplayBySelectionResult.Done>()
        done.skipped shouldBe listOf(a)
        done.replayed shouldBe listOf(b)
        requeue.calls.size shouldBe 2
    }

    @Test
    fun `a version of another entity never reaches requeue`() {
        val requeue = FakeRequeue()
        run(replica(), FakeSource(pool), requeue)
        requeue.calls.map { it.second } shouldBe listOf("doc-a", "doc-b")
    }

    @Test
    fun `matchedTruncated follows the cap`() {
        val done =
            ReplayBySelection(replica(), FakeSource(pool), FakeRequeue())
                .handle(ReplayBySelectionRequest(tenant, holder, scope, 1), deadline)
                .shouldBeInstanceOf<ReplayBySelectionResult.Done>()
        done.replayed shouldBe listOf(a)
        done.matchedTruncated shouldBe true
    }
}
