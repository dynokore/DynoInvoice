package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.structure.BusinessUnitRecord
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupRecord
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.StructureView
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.contract.model.BusinessUnitResponse
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.StructureNode

internal fun groupResponse(
    group: GroupRecord,
    tenantRef: String,
) = GroupResponse(
    group.ref,
    tenantRef,
    group.name,
    GroupResponse.GroupKind.valueOf(group.kind.name),
    group.gstn,
)

internal fun unitResponse(
    unit: BusinessUnitRecord,
    tenantRef: String,
) = BusinessUnitResponse(unit.ref, tenantRef, unit.entityRef, unit.name, unit.code)

internal fun entityResponse(entity: EntityRecord) =
    EntityResponse(
        entity.ref,
        entity.legalName,
        EntityResponse.LifecycleState.valueOf(entity.lifecycleState),
        nodeOf(entityNode(entity)),
    )

internal fun viewResponse(view: StructureView) =
    EntityResponse(view.ref, view.name, EntityResponse.LifecycleState.valueOf(view.lifecycleState), nodeOf(view.node))

internal fun nodeOf(node: StructureNodeState) =
    StructureNode(
        StructureNode.Kind.valueOf(node.kind.name),
        node.retired,
        node.name,
        emptyList(),
        node.structureVersion,
        node.parentRef,
        node.code,
        node.uen,
        node.gstn,
    )
