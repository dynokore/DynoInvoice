package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.status.EntityStatusEntry
import com.dynokore.gst.tenancy.application.status.StatusChange
import com.dynokore.gst.tenancy.application.structure.BusinessUnitRecord
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupRecord
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.StructureView
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.contract.model.BusinessUnitResponse
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.EntityStatusResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.contract.model.StructureNode
import com.dynokore.gst.tenancy.contract.model.StructureNodeProfileInner
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.ObligationStartBasis
import com.dynokore.gst.tenancy.domain.StatusKind

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
        node.profile.map { StructureNodeProfileInner(it.attribute, it.value, it.effectiveDate) },
        node.structureVersion,
        node.parentRef,
        node.code,
        node.uen,
        node.gstn,
    )

internal fun statusResponse(
    entityRef: String,
    entry: EntityStatusEntry,
) = EntityStatusResponse(
    entityRef,
    EntityStatusResponse.StatusKind.valueOf(entry.kind.name),
    entry.effectiveFrom,
    entry.partiallyExempt,
    entry.excludedBusinessStatus?.let { EntityStatusResponse.ExcludedBusinessStatus.valueOf(it.name) },
    entry.basis,
    entry.evidenceRef,
    entry.obligationStartDate,
    entry.obligationStartBasis?.let { EntityStatusResponse.ObligationStartBasis.valueOf(it.name) },
    entry.irasReference,
    entry.obligationStartOverride,
    entry.registrationStartDate,
)

/** The answer when no obligation start can be derived yet: nothing was recorded and the date stays outstanding. */
internal fun outstandingStatusResponse(
    entityRef: String,
    request: SetEntityStatusRequest,
) = EntityStatusResponse(
    entityRef,
    EntityStatusResponse.StatusKind.valueOf(request.statusKind.name),
    request.effectiveFrom,
    registrationStartDate = request.registrationStartDate,
)

internal fun statusChangeOf(request: SetEntityStatusRequest) =
    StatusChange(
        StatusKind.valueOf(request.statusKind.name),
        request.effectiveFrom,
        request.partiallyExempt,
        request.excludedBusinessStatus?.let { ExcludedBusinessStatus.valueOf(it.name) },
        request.basis,
        request.evidenceRef,
        request.obligationStartDate,
        request.obligationStartBasis?.let { ObligationStartBasis.valueOf(it.name) },
        request.irasReference,
        request.obligationStartOverride,
        request.registrationStartDate,
    )
