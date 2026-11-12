package com.dynokore.gst.ingestion.adapters.permission

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20953: the permission port over a real [AuthorityReplica], fed as MachineTypePinTest feeds it. */
class ReplicaEntityPermissionTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entity = "E-001"
    private val actor = "identity:P-1"
    private val key = "entity-config.manage"
    private val role = "a-test-role"

    private val roles =
        OperationRoleMap { k -> if (k == key) mapOf(role to CellAllowance.PERMITTED) else emptyMap() }

    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor(): String? = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun replica(
        current: Boolean,
        granted: Boolean,
    ): AuthorityReplica {
        val none = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = none)
        if (granted) {
            replica.accept(
                AuthorityChanged(
                    tenant,
                    actor,
                    1,
                    AuthorityRecord.RoleGrantRecord(
                        "g1",
                        RoleGrantState.ACTIVE,
                        RoleGrant(role, GrantScope.ENTITY, entity, now.minusSeconds(60)),
                    ),
                ),
            )
            replica.observeHead(1)
        }
        if (current) replica.onSnapshotComplete(if (granted) 1 else 0)
        return replica
    }

    private fun permission(replica: AuthorityReplica?) =
        ReplicaEntityPermission(replica, tenants, clock, Duration.ofSeconds(30))

    @Test
    fun `INV-AC-20953 no replica bound is refused`() {
        permission(null).holds(actor, entity, key) shouldBe false
    }

    @Test
    fun `INV-AC-20953 a replica that received no feed (DEGRADED) is refused`() {
        permission(replica(current = false, granted = false)).holds(actor, entity, key) shouldBe false
    }

    @Test
    fun `INV-AC-20953 a current replica that grants the exact key on the entity is allowed`() {
        permission(replica(current = true, granted = true)).holds(actor, entity, key) shouldBe true
    }

    @Test
    fun `INV-AC-20953 a current replica that does not grant the key is refused`() {
        val p = permission(replica(current = true, granted = false))
        p.holds(actor, entity, key) shouldBe false
        permission(replica(current = true, granted = true)).holds(actor, "E-002", key) shouldBe false
        permission(replica(current = true, granted = true)).holds("identity:P-2", entity, key) shouldBe false
    }

    @Test
    fun `INV-AC-20953 a decide that throws is refused`() {
        var entered = 0
        val throwing =
            ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
                entered++
                error("the fallback fails")
            }
        // a replica with no feed asks its fallback inside decide, so decide itself is entered and fails
        val degraded = AuthorityReplica(clock, roles, fallback = throwing)
        permission(degraded).holds(actor, entity, key) shouldBe false
        entered shouldBe 1
    }

    @Test
    fun `INV-AC-20953 the look-alike key entity-config managed is refused for a holder of entity-config manage`() {
        val p = permission(replica(current = true, granted = true))
        p.holds(actor, entity, "entity-config.managed") shouldBe false
        p.holds(actor, entity, "Entity-Config.Manage") shouldBe false
        p.holds(actor, entity, "entity-config.manage ") shouldBe false
    }

    @Test
    fun `INV-AC-20953 an allowed decision with a source other than LOCAL is refused`() {
        val allowing =
            ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
                FallbackResult.Found(AuthorityVerdict(true))
            }
        val degraded = AuthorityReplica(clock, roles, fallback = allowing)
        val decision =
            degraded.decide(
                AuthorizationRequest(tenant, Principal(actor, PrincipalKind.HUMAN), entity, key),
                Deadline.after(Duration.ofSeconds(30), clock),
            )
        decision.allowed shouldBe true
        decision.source shouldBe DecisionSource.FALLBACK
        permission(degraded).holds(actor, entity, key) shouldBe false
    }
}
