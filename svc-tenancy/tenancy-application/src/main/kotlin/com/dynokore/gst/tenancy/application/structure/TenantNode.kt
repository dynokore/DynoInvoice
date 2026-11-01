package com.dynokore.gst.tenancy.application.structure

import com.dynokore.gst.tenancy.application.tenant.TenantRecord

/**
 * The one construction of a TENANT node (INV-Q-15377 A): kind TENANT, `retired`, the legal name, no code, an empty
 * profile, the stored structure version and the stored environment. getEntityStructure and the replay both call it.
 */
fun tenantNode(tenant: TenantRecord): StructureNodeState =
    StructureNodeState(
        NodeType.TENANT,
        null,
        tenant.retired,
        tenant.legalName,
        null,
        tenant.structureVersion,
        environment = tenant.environment,
    )
