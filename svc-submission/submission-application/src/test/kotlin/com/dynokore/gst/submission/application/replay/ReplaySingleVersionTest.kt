package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.domain.ordering.LifecycleState
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
import java.time.ZoneOffset
import java.util.UUID

class ReplaySingleVersionTest {
    private companion object {
        const val OTHER_KEY = "submission.view-status"
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val otherTenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    private fun roleOf(key: String): String =
        roleMap
            .rolesFor(key)
            .keys
            .sorted()
            .first()

    private fun roleOfOnly(
        key: String,
        notFor: String,
    ): String = (roleMap.rolesFor(key).keys - roleMap.rolesFor(notFor).keys).sorted().first()

    /** A replica where [principal] holds [role] on entity E1 only. */
    private fun replicaFor(
        role: String,
        principal: Principal = holder,
    ) = AuthorityReplica(clock, roleMap).also {
        it.onSnapshotComplete(0)
        val grant = RoleGrant(role, GrantScope.ENTITY, "E1", clock.instant().minusSeconds(60))
        it.accept(
            AuthorityChanged(
                tenant,
                principal.id,
                1,
                AuthorityRecord.RoleGrantRecord("$role-E1", RoleGrantState.ACTIVE, grant),
            ),
        )
    }

    private fun allowedForE1() = replicaFor(roleOf(ReplayActions.REPLAY))

    private class FakeLookup(
        private val rows: Map<Triple<TenantId, String, Int>, ReplayVersionView>,
    ) : ReplayVersionLookup {
        override fun find(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ) = rows[Triple(tenant, docUuid, version)]
    }

    private class FakeRequeue(
        private val conflict: Boolean = false,
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
                conflict -> RequeueOutcome.IN_FLIGHT_CONFLICT
                queued.add(docUuid to version) -> RequeueOutcome.REQUEUED
                else -> RequeueOutcome.ALREADY_QUEUED
            }
        }
    }

    private val lookup =
        FakeLookup(
            mapOf(
                Triple(tenant, "D", 2) to ReplayVersionView("E1", LifecycleState.DEAD_LETTERED),
                Triple(tenant, "D2", 1) to ReplayVersionView("E1", LifecycleState.QUEUED),
                Triple(tenant, "D3", 1) to ReplayVersionView("E1", LifecycleState.SENT),
                Triple(tenant, "D4", 1) to ReplayVersionView("E2", LifecycleState.DEAD_LETTERED),
                Triple(otherTenant, "D5", 1) to ReplayVersionView("E1", LifecycleState.DEAD_LETTERED),
            ),
        )

    private fun handle(
        replica: AuthorityReplica,
        requeue: ReplayRequeue,
        doc: String,
        version: Int,
        who: Principal = holder,
    ) = ReplaySingleVersion(replica, lookup, requeue)
        .handle(ReplaySingleVersionRequest(tenant, who, doc, version), deadline)

    @Test
    fun `INV-AC-20590 D2 handled twice is Requeued then AlreadyQueued with one requeue effect`() {
        val requeue = FakeRequeue()
        val uc = ReplaySingleVersion(allowedForE1(), lookup, requeue)
        val req = ReplaySingleVersionRequest(tenant, holder, "D", 2)
        uc.handle(req, deadline) shouldBe ReplaySingleVersionResult.Requeued
        uc.handle(req, deadline) shouldBe ReplaySingleVersionResult.AlreadyQueued
        requeue.calls shouldBe listOf(Triple(tenant, "D", 2), Triple(tenant, "D", 2))
    }

    @Test
    fun `INV-AC-20590 the QUEUED version is AlreadyQueued`() {
        val requeue = FakeRequeue().also { it.requeue(tenant, "D2", 1) }
        handle(allowedForE1(), requeue, "D2", 1) shouldBe ReplaySingleVersionResult.AlreadyQueued
    }

    @Test
    fun `INV-AC-20590 an in-flight conflict of the port is passed through`() {
        handle(allowedForE1(), FakeRequeue(conflict = true), "D", 2) shouldBe
            ReplaySingleVersionResult.InFlightConflict
    }

    @Test
    fun `INV-AC-20590 the SENT version is NotReplayable carrying SENT and requeue is never called`() {
        val requeue = FakeRequeue()
        handle(allowedForE1(), requeue, "D3", 1) shouldBe
            ReplaySingleVersionResult.NotReplayable(LifecycleState.SENT)
        requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20590 the unknown and the second-tenant version are NotFound with 0 replica and 0 requeue calls`() {
        val requeue = FakeRequeue()
        // a replica with no snapshot answers AUTHORITY_UNAVAILABLE: NotFound proves the replica was not reached
        val uc = ReplaySingleVersion(AuthorityReplica(clock, roleMap), lookup, requeue)
        uc.handle(ReplaySingleVersionRequest(tenant, holder, "unknown", 1), deadline) shouldBe
            ReplaySingleVersionResult.NotFound
        uc.handle(ReplaySingleVersionRequest(tenant, holder, "D", 3), deadline) shouldBe
            ReplaySingleVersionResult.NotFound
        uc.handle(ReplaySingleVersionRequest(tenant, holder, "D5", 1), deadline) shouldBe
            ReplaySingleVersionResult.NotFound
        requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20590 the entity scope is the entityRef of the looked-up version and the key submission-replay`() {
        ReplayActions.REPLAY shouldBe "submission.replay"
        // the replica is final, so the scope is proved by decision: a grant on E1 allows the E1 version only,
        // a grant on E2 allows the E2 version only
        val onE2 =
            AuthorityReplica(clock, roleMap).also {
                it.onSnapshotComplete(0)
                val role = roleOf(ReplayActions.REPLAY)
                val grant = RoleGrant(role, GrantScope.ENTITY, "E2", clock.instant().minusSeconds(60))
                it.accept(
                    AuthorityChanged(
                        tenant,
                        holder.id,
                        1,
                        AuthorityRecord.RoleGrantRecord("$role-E2", RoleGrantState.ACTIVE, grant),
                    ),
                )
            }
        handle(allowedForE1(), FakeRequeue(), "D", 2) shouldBe ReplaySingleVersionResult.Requeued
        handle(onE2, FakeRequeue(), "D", 2).shouldBeInstanceOf<ReplaySingleVersionResult.Forbidden>()
        handle(onE2, FakeRequeue(), "D4", 1) shouldBe ReplaySingleVersionResult.Requeued
    }

    @Test
    fun `INV-AC-20590 the caller allowed for E1 only is Forbidden for the version of E2 with 0 requeue calls`() {
        val requeue = FakeRequeue()
        handle(allowedForE1(), requeue, "D4", 1) shouldBe
            ReplaySingleVersionResult.Forbidden(DecisionReason.NO_GRANT)
        handle(allowedForE1(), requeue, "D", 2).shouldBeInstanceOf<ReplaySingleVersionResult.Requeued>()
        requeue.calls.size shouldBe 1
    }

    @Test
    fun `INV-AC-20590 a refused caller and a role without the key are Forbidden with 0 requeue calls`() {
        val requeue = FakeRequeue()
        handle(allowedForE1(), requeue, "D", 2, who = stranger) shouldBe
            ReplaySingleVersionResult.Forbidden(DecisionReason.NO_GRANT)
        val wrongRole = replicaFor(roleOfOnly(OTHER_KEY, ReplayActions.REPLAY))
        handle(wrongRole, requeue, "D", 2).shouldBeInstanceOf<ReplaySingleVersionResult.Forbidden>()
        requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20590 AUTHORITY_UNAVAILABLE is Forbidden with 0 requeue calls`() {
        val requeue = FakeRequeue()
        handle(AuthorityReplica(clock, roleMap), requeue, "D", 2) shouldBe
            ReplaySingleVersionResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        requeue.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20590 the look-alike key submission-replays and a role name do not allow it`() {
        ReplayActions.REPLAY shouldBe "submission.replay"
        val replica = allowedForE1()
        replica.decide(AuthorizationRequest(tenant, holder, "E1", "submission.replay"), deadline).allowed shouldBe true
        replica
            .decide(AuthorizationRequest(tenant, holder, "E1", "submission.replays"), deadline)
            .allowed shouldBe false
        replica
            .decide(AuthorizationRequest(tenant, holder, "E1", roleOf(ReplayActions.REPLAY)), deadline)
            .allowed shouldBe false
    }
}
