package com.dynokore.gst.notification.adapters.tenants

import com.dynokore.gst.notification.application.alerting.RegisteredTenantList
import com.dynokore.gst.notification.application.alerting.RegisteredTenantSource
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica

/**
 * The registered tenants read from the structure replica (INV-REQ-1200; INV-Q-8996: no synchronous call). The list is
 * complete only once the replica holds a snapshot; otherwise it is [RegisteredTenantList.Incomplete] and the caller
 * retries. A tenant is a held TENANT node that is not retired; other node kinds, retired tenants and the reserved
 * platform tenant are left out.
 */
class StructureRegisteredTenants(
    private val structure: StructureReplica,
) : RegisteredTenantSource {
    override fun registered(): RegisteredTenantList {
        if (!structure.isSynced()) return RegisteredTenantList.Incomplete
        val tenants =
            structure.replica
                .heldWhere { true }
                .filterValues { it.kind == StructureNodeKind.TENANT && !it.retired }
                .keys
                .map { it.tenant }
                .filterNot { it.isPlatform }
                .toSet()
        return RegisteredTenantList.Complete(tenants)
    }
}
