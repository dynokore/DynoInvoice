package com.dynokore.gst.masterdata.adapters.permission

import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionSource
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

/** The `entity-config.confirm` binding asks the replica for exactly that action and fails closed (INV-Q-10025). */
class ReplicaConfirmerPermissionTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val asked = mutableListOf<String>()
    private val replica =
        AuthorityReplica(
            clock,
            OperationRoleMap { key ->
                asked += key
                if (key == "entity-config.confirm") mapOf("tax.approver" to CellAllowance.PERMITTED) else emptyMap()
            },
        ).also { it.onSnapshotComplete(0) }
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

    private fun hold(
        person: String,
        role: String,
        entity: String,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                person,
                1,
                AuthorityRecord.RoleGrantRecord(
                    "g-$person",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.ENTITY, entity, now.minusSeconds(60)),
                ),
            ),
        )
        replica.observeHead(1)
    }

    @Test
    fun `the holder is allowed from the local replica and only the action entity-config confirm is asked`() {
        hold("P-1", "tax.approver", "ENT-1")
        val answer = ReplicaConfirmerPermission(replica, tenants, clock).decide(PersonRef("P-1"), EntityRef("ENT-1"))
        assertEquals(true, answer.allowed)
        assertEquals(DecisionSource.LOCAL, answer.source)
        assertEquals(setOf("entity-config.confirm"), asked.toSet())
    }

    @Test
    fun `no replica, a failing tenant lookup and a missing grant answer not allowed`() {
        hold("P-1", "tax.approver", "ENT-1")
        assertEquals(
            false,
            ReplicaConfirmerPermission(null, tenants, clock).decide(PersonRef("P-1"), EntityRef("ENT-1")).allowed,
        )
        val broken =
            object : TenantContext by tenants {
                override fun current(): TenantId = error("no tenant bound")
            }
        assertEquals(
            false,
            ReplicaConfirmerPermission(replica, broken, clock).decide(PersonRef("P-1"), EntityRef("ENT-1")).allowed,
        )
        assertEquals(
            false,
            ReplicaConfirmerPermission(replica, tenants, clock).decide(PersonRef("P-2"), EntityRef("ENT-1")).allowed,
        )
        assertEquals(
            false,
            ReplicaConfirmerPermission(replica, tenants, clock).decide(PersonRef("P-1"), EntityRef("ENT-2")).allowed,
        )
    }
}
