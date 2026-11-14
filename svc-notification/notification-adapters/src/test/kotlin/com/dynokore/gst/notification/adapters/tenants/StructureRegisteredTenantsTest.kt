package com.dynokore.gst.notification.adapters.tenants

import com.dynokore.gst.notification.application.alerting.RegisteredTenantList
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class StructureRegisteredTenantsTest {
    private val t1 = TenantId(UUID.randomUUID())
    private val t2 = TenantId(UUID.randomUUID())
    private val t4 = TenantId(UUID.randomUUID())
    private var version = 0L

    private fun node(
        kind: StructureNodeKind,
        retired: Boolean = false,
    ) = StructureNode(kind, null, retired, "n", null, emptyList(), ++version)

    private fun StructureReplica.put(
        tenant: TenantId,
        ref: String,
        kind: StructureNodeKind,
        retired: Boolean = false,
    ) = accept(OrganisationStructureChanged(tenant, ref, StructureChange.UPDATED, node(kind, retired)))

    private fun replica(synced: Boolean = true): StructureReplica {
        val r = StructureReplica(Clock.systemUTC())
        r.put(t1, t1.value.toString(), StructureNodeKind.TENANT)
        r.put(t2, t2.value.toString(), StructureNodeKind.TENANT)
        r.put(t4, t4.value.toString(), StructureNodeKind.TENANT, retired = true)
        r.put(t1, "E-1", StructureNodeKind.ENTITY)
        r.put(TenantId.PLATFORM, "P", StructureNodeKind.TENANT)
        if (synced) r.onSnapshotComplete(version)
        return r
    }

    private fun tenants(r: StructureReplica) =
        (StructureRegisteredTenants(r).registered() as RegisteredTenantList.Complete).tenants

    @Test
    fun `INV-AC-22425 a replica that is not synced gives Incomplete`() {
        StructureRegisteredTenants(replica(synced = false)).registered() shouldBe RegisteredTenantList.Incomplete
    }

    @Test
    fun `INV-AC-22425 the set holds each non-retired TENANT node and nothing else`() {
        tenants(replica()) shouldBe setOf(t1, t2)
    }

    @Test
    fun `INV-AC-22425 a node retired by a later event leaves the set`() {
        val r = replica()
        r.put(t2, t2.value.toString(), StructureNodeKind.TENANT, retired = true)
        tenants(r) shouldBe setOf(t1)
    }

    @Test
    fun `INV-AC-22425 the look-alike ENTITY node with a tenant-like ref is not in the set`() {
        val r = replica()
        val other = TenantId(UUID.randomUUID())
        r.put(other, other.value.toString(), StructureNodeKind.ENTITY)
        tenants(r) shouldBe setOf(t1, t2)
    }
}
