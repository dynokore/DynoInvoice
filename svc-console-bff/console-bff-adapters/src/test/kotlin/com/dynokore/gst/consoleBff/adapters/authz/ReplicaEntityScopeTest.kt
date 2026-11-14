package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-21105 (scope half): the entities a principal may read are those the replica allows for exception.view. */
class ReplicaEntityScopeTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-14T10:00:00Z")
    }

    private val clock = FixedClock()
    private val tenant = TenantId.of("11111111-1111-1111-1111-111111111111")
    private val otherTenant = TenantId.of("22222222-2222-2222-2222-222222222222")
    private val replica =
        AuthorityReplica(
            clock,
            OperationRoleMap { key ->
                if (key == "exception.view") mapOf("fixture-holder" to CellAllowance.PERMITTED) else emptyMap()
            },
        )
    private var version = 0L

    private fun grant(
        principal: String,
        entity: String,
        role: String,
    ) = replica.accept(
        AuthorityChanged(
            tenant,
            principal,
            ++version,
            AuthorityRecord.RoleGrantRecord(
                "g-$principal-$entity",
                RoleGrantState.ACTIVE,
                RoleGrant(role, GrantScope.ENTITY, entity, clock.instant().minusSeconds(60)),
            ),
        ),
    )

    private fun scope(permissions: ReadPermissions = CatalogueReadPermissions()) =
        ReplicaEntityScope(replica, permissions, clock, Duration.ofSeconds(1))

    private fun seeded(): ReplicaEntityScope {
        grant("p1", "E-001", "fixture-holder")
        grant("p1", "E-002", "other-role")
        replica.onSnapshotComplete(version)
        return scope()
    }

    @Test
    fun `INV-AC-21105 entitiesOf keeps only the entities the replica allows for exception view`() {
        seeded().entitiesOf(tenant.toString(), "p1") shouldBe setOf("E-001")
    }

    @Test
    fun `INV-AC-21105 entitiesOf is empty for a principal with no grant and for another tenant`() {
        val scope = seeded()
        scope.entitiesOf(tenant.toString(), "p2") shouldBe emptySet()
        scope.entitiesOf(otherTenant.toString(), "p1") shouldBe emptySet()
    }

    @Test
    fun `INV-AC-21105 an unknown permission key for the route gives an empty set`() {
        grant("p1", "E-001", "fixture-holder")
        replica.onSnapshotComplete(version)
        scope { _, _ -> null }.entitiesOf(tenant.toString(), "p1") shouldBe emptySet()
    }

    @Test
    fun `INV-AC-21105 a malformed tenant id gives an empty set`() {
        seeded().entitiesOf("not-a-tenant", "p1") shouldBe emptySet()
    }
}
