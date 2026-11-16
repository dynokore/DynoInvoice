package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.structure.ReplicaEntityDirectory
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.PeriodRange
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-T-15799 filing entity: the entity a grant's expiry notices are filed under, read through the real
 * [ReplicaEntityDirectory] over a structure replica that also holds a second tenant.
 */
class FilingEntityTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val other = TenantId(UUID.randomUUID())
    private val replica = StructureReplica(Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC))
    private var version = 0L
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "user:test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val directory = ReplicaEntityDirectory(replica, tenants)

    private fun node(
        of: TenantId,
        ref: String,
        kind: StructureNodeKind,
        parent: String? = null,
    ) {
        version++
        replica.accept(
            OrganisationStructureChanged(
                of,
                ref,
                StructureChange.CREATED,
                StructureNode(kind, parent, false, ref, null, emptyList(), version),
            ),
        )
    }

    private fun entities(
        of: TenantId,
        vararg refs: String,
        parent: String? = null,
    ) = refs.forEach { node(of, it, StructureNodeKind.ENTITY, parent) }

    private fun filing(scope: Scope) = filingEntityOf(scope, directory)

    private val tenantScope get() = Scope.Tenant(TenantRef(tenant.toString()))

    @Test
    fun `INV-AC-2250 a tenant scope over ENT-9 ENT-10 and ENT-2 files under ENT-10 by string order, not ENT-9 or ENT-2`() {
        entities(tenant, "ENT-9", "ENT-10", "ENT-2")
        assertThat(filing(tenantScope)).isEqualTo(EntityRef("ENT-10"))
    }

    @Test
    fun `INV-AC-2250 the result does not depend on the order the entities arrived in`() {
        entities(tenant, "ENT-9", "ENT-10")
        assertThat(filing(tenantScope)).isEqualTo(EntityRef("ENT-10"))
        val reversed = StructureReplica(Clock.systemUTC())
        listOf("ENT-10", "ENT-9").forEachIndexed { i, ref ->
            reversed.accept(
                OrganisationStructureChanged(
                    tenant,
                    ref,
                    StructureChange.CREATED,
                    StructureNode(StructureNodeKind.ENTITY, null, false, ref, null, emptyList(), i + 1L),
                ),
            )
        }
        assertThat(filingEntityOf(tenantScope, ReplicaEntityDirectory(reversed, tenants))).isEqualTo(EntityRef("ENT-10"))
    }

    @Test
    fun `INV-AC-2250 a group scope files under the smallest of its own entities only`() {
        node(tenant, "GRP-1", StructureNodeKind.GROUP)
        entities(tenant, "ENT-9", "ENT-30", "ENT-7", parent = "GRP-1")
        entities(tenant, "ENT-1")
        val scope = Scope.Group(GroupRef("GRP-1"), TenantRef(tenant.toString()))
        assertThat(filing(scope)).isEqualTo(EntityRef("ENT-30"))
    }

    @Test
    fun `INV-AC-2250 an entity scope files under its own entity even when a smaller one exists`() {
        entities(tenant, "ENT-1", "ENT-5")
        assertThat(filing(Scope.Entity(EntityRef("ENT-5")))).isEqualTo(EntityRef("ENT-5"))
    }

    @Test
    fun `INV-AC-2250 an entity-period scope files under its own entity even when a smaller one exists`() {
        entities(tenant, "ENT-1", "ENT-5")
        val periods = PeriodRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"))
        assertThat(filing(Scope.EntityPeriod(EntityRef("ENT-5"), periods))).isEqualTo(EntityRef("ENT-5"))
    }

    @Test
    fun `INV-AC-2250 a tenant scope over no entity files under none`() {
        assertThat(filing(tenantScope)).isNull()
    }

    @Test
    fun `INV-AC-2250 a group scope over no entity files under none even when the tenant has entities`() {
        node(tenant, "GRP-1", StructureNodeKind.GROUP)
        entities(tenant, "ENT-1")
        assertThat(filing(Scope.Group(GroupRef("GRP-1"), TenantRef(tenant.toString())))).isNull()
    }

    @Test
    fun `INV-AC-2250 a consent-derived scope files under none`() {
        entities(tenant, "ENT-1")
        assertThat(filing(Scope.ConsentDerived(TenantRef(tenant.toString())) { true })).isNull()
    }

    @Test
    fun `INV-AC-2250 an entity of another tenant is never compared`() {
        entities(other, "ENT-0", "ENT-00")
        entities(tenant, "ENT-5")
        assertThat(filing(tenantScope)).isEqualTo(EntityRef("ENT-5"))
        node(other, "GRP-1", StructureNodeKind.GROUP)
        entities(other, "ENT-A", parent = "GRP-1")
        assertThat(filing(Scope.Group(GroupRef("GRP-1"), TenantRef(tenant.toString())))).isNull()
    }
}
