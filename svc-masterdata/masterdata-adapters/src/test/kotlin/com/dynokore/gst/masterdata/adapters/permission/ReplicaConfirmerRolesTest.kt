package com.dynokore.gst.masterdata.adapters.permission

import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The confirmer roles read through the replica's public holder query, fail closed (INV-Q-8121 A). */
class ReplicaConfirmerRolesTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val replica = AuthorityReplica(clock, OperationRoleMap { emptyMap() }).also { it.onSnapshotComplete(0) }
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
    private var version = 0L

    private fun hold(
        person: String,
        role: String,
        scope: GrantScope,
        ref: String,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                person,
                ++version,
                AuthorityRecord.RoleGrantRecord(
                    "g$version",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, scope, ref, now.minusSeconds(60)),
                ),
            ),
        )
        replica.observeHead(version)
    }

    private fun roles(
        person: String,
        entity: String,
        bound: AuthorityReplica? = replica,
    ) = ReplicaConfirmerRoles(bound, tenants, clock).rolesOf(PersonRef(person), EntityRef(entity))

    @Test
    fun `a confirmer role held on the entity is answered and a role on another entity or outside the set is not`() {
        hold("P-1", "entity.admin", GrantScope.ENTITY, "ENT-1")
        hold("P-2", "entity.admin", GrantScope.ENTITY, "ENT-OTHER")
        hold("P-3", "agent.operator", GrantScope.ENTITY, "ENT-1")
        assertEquals(setOf("entity.admin"), roles("P-1", "ENT-1"))
        assertEquals(emptySet<String>(), roles("P-2", "ENT-1"))
        assertEquals(emptySet<String>(), roles("P-3", "ENT-1"))
        assertEquals(emptySet<String>(), roles("P-NOBODY", "ENT-1"))
    }

    @Test
    fun `no replica and a replica that never received a snapshot answer the empty set`() {
        assertEquals(emptySet<String>(), roles("P-1", "ENT-1", null))
        val cold = AuthorityReplica(clock, OperationRoleMap { emptyMap() })
        assertEquals(emptySet<String>(), roles("P-1", "ENT-1", cold))
    }
}
