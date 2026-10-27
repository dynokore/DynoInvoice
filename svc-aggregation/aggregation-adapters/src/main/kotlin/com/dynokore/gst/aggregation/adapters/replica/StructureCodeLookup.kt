package com.dynokore.gst.aggregation.adapters.replica

import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The registered short codes (INV-REQ-436 item 3, INV-Q-11262 A) from the structure replica of the bound tenant: the
 * `code` of the node of the right level. A node that is absent, retired, of another level or without a code gives
 * null. It reads what the replica holds and calls nobody.
 */
class StructureCodeLookup(
    private val structure: StructureReplica,
    private val tenants: TenantContext,
) : ShortCodes {
    override fun entityCode(entityRef: String): String? = codeOf(entityRef, StructureNodeKind.ENTITY)

    override fun businessUnitCode(businessUnitRef: String): String? =
        codeOf(businessUnitRef, StructureNodeKind.BUSINESS_UNIT)

    private fun codeOf(
        nodeRef: String,
        level: StructureNodeKind,
    ): String? =
        structure.replica
            .peek(StructureKey(tenants.current(), nodeRef))
            ?.takeIf { it.kind == level && !it.retired }
            ?.code
}
