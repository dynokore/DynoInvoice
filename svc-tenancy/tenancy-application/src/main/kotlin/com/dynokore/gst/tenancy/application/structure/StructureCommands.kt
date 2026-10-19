package com.dynokore.gst.tenancy.application.structure

import com.dynokore.gst.tenancy.domain.CodeOutcome
import com.dynokore.gst.tenancy.domain.CodeRefusal
import com.dynokore.gst.tenancy.domain.CodeRules
import com.dynokore.gst.tenancy.domain.ShortCode
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** A request to create a group (contract CreateGroupRequest). */
data class NewGroup(
    val name: String,
    val kind: GroupKind,
    val gstn: String?,
)

/** A request to create a registered entity (contract CreateEntityRequest). */
data class NewEntity(
    val groupRef: String,
    val legalName: String,
    val constitution: String,
    val uen: String?,
    val gstn: String?,
    val code: String?,
)

/** A request to register a business unit (contract RegisterBusinessUnitRequest). */
data class NewBusinessUnit(
    val entityRef: String,
    val name: String,
    val code: String,
)

/**
 * The structure commands of the tenancy store (INV-REQ-850): each runs in one transaction bound to the caller's tenant,
 * and publishes OrganisationStructureChanged in that transaction with a per-node `structureVersion` from one sequence
 * (INV-API-3327). Entities are written as DRAFT only; the later lifecycle commands are other tasks'.
 */
class StructureCommands(
    private val tx: TransactionRunner,
    private val store: StructureStore,
    private val publisher: StructureChangePublisher,
) {
    fun createGroup(
        tenant: TenantId,
        tenantRef: String,
        request: NewGroup,
    ): StructureOutcome<GroupRecord> =
        tx.inTransaction {
            val repeat = store.groupByName(request.name)
            if (repeat != null) return@inTransaction StructureOutcome.Done(repeat, repeated = true)
            val id = UUID.randomUUID()
            val group =
                GroupRecord(
                    id,
                    id.toString(),
                    request.name,
                    request.kind,
                    request.gstn,
                    retired = false,
                    structureVersion = store.nextStructureVersion(),
                )
            store.insertGroup(group)
            publisher.publish(
                change(
                    tenant,
                    group.ref,
                    NodeType.GROUP,
                    ChangeType.CREATED,
                    tenantRef,
                    null,
                    groupNode(group, tenantRef),
                ),
            )
            StructureOutcome.Done(group)
        }

    fun createEntity(
        tenant: TenantId,
        request: NewEntity,
    ): StructureOutcome<EntityRecord> =
        tx.inTransaction {
            val group = store.group(request.groupRef) ?: return@inTransaction StructureOutcome.NotFound
            val repeat = store.draftEntity(group.ref, request.legalName)
            if (repeat != null) return@inTransaction StructureOutcome.Done(repeat, repeated = true)
            val code =
                when (val outcome = CodeRules.entityCode(request.code, request.uen, taken())) {
                    is CodeOutcome.Refused -> return@inTransaction refused(outcome.reason)
                    is CodeOutcome.Accepted -> outcome.value
                }
            val id = UUID.randomUUID()
            val entity =
                EntityRecord(
                    id,
                    id.toString(),
                    group.ref,
                    request.legalName,
                    request.constitution,
                    request.uen,
                    request.gstn,
                    code.value,
                    DRAFT,
                    retired = false,
                    structureVersion = store.nextStructureVersion(),
                )
            store.insertEntity(entity)
            publisher.publish(
                change(tenant, entity.ref, NodeType.ENTITY, ChangeType.CREATED, group.ref, null, entityNode(entity)),
            )
            StructureOutcome.Done(entity)
        }

    fun registerBusinessUnit(
        tenant: TenantId,
        request: NewBusinessUnit,
    ): StructureOutcome<BusinessUnitRecord> =
        tx.inTransaction {
            val entity = store.entity(request.entityRef) ?: return@inTransaction StructureOutcome.NotFound
            val repeat = store.businessUnitByCode(entity.ref, request.code)
            if (repeat != null) return@inTransaction StructureOutcome.Done(repeat, repeated = true)
            val code =
                when (val outcome = CodeRules.businessUnitCode(request.code, taken())) {
                    is CodeOutcome.Refused -> return@inTransaction refused(outcome.reason)
                    is CodeOutcome.Accepted -> outcome.value
                }
            val id = UUID.randomUUID()
            val unit =
                BusinessUnitRecord(
                    id,
                    id.toString(),
                    entity.ref,
                    request.name,
                    code.value,
                    retired = false,
                    structureVersion = store.nextStructureVersion(),
                )
            store.insertBusinessUnit(unit)
            publisher.publish(
                change(
                    tenant,
                    entity.ref,
                    NodeType.BUSINESS_UNIT,
                    ChangeType.CREATED,
                    entity.ref,
                    null,
                    unitNode(unit),
                    unit.ref,
                ),
            )
            StructureOutcome.Done(unit)
        }

    fun moveBusinessUnit(
        tenant: TenantId,
        unitRef: String,
        toEntityRef: String,
    ): StructureOutcome<BusinessUnitRecord> =
        tx.inTransaction {
            val unit = store.businessUnit(unitRef) ?: return@inTransaction StructureOutcome.NotFound
            val target = store.entity(toEntityRef) ?: return@inTransaction StructureOutcome.NotFound
            if (unit.entityRef == target.ref) return@inTransaction StructureOutcome.Done(unit, repeated = true)
            val version = store.nextStructureVersion()
            store.moveBusinessUnit(unit.ref, target.ref, version)
            val moved = unit.copy(entityRef = target.ref, structureVersion = version)
            publisher.publish(
                change(
                    tenant,
                    target.ref,
                    NodeType.BUSINESS_UNIT,
                    ChangeType.MOVED,
                    target.ref,
                    unit.entityRef,
                    unitNode(moved),
                    unit.ref,
                ),
            )
            StructureOutcome.Done(moved)
        }

    fun updateBusinessUnit(
        tenant: TenantId,
        unitRef: String,
        name: String,
        code: String,
    ): StructureOutcome<BusinessUnitRecord> =
        tx.inTransaction {
            val unit = store.businessUnit(unitRef) ?: return@inTransaction StructureOutcome.NotFound
            if (unit.name == name &&
                unit.code == code
            ) {
                return@inTransaction StructureOutcome.Done(unit, repeated = true)
            }
            val newCode =
                when (val outcome = changedCode(unit.code, code)) {
                    is CodeOutcome.Refused -> return@inTransaction refused(outcome.reason)
                    is CodeOutcome.Accepted -> outcome.value
                }
            val version = store.nextStructureVersion()
            store.updateBusinessUnit(unit.ref, name, newCode.value, version)
            val updated = unit.copy(name = name, code = newCode.value, structureVersion = version)
            publisher.publish(
                change(
                    tenant,
                    unit.entityRef,
                    NodeType.BUSINESS_UNIT,
                    ChangeType.UPDATED,
                    unit.entityRef,
                    null,
                    unitNode(updated),
                    unit.ref,
                ),
            )
            StructureOutcome.Done(updated)
        }

    /**
     * Changes an entity's short code. No operation of the published contract does this (INV-Q-7385), so it is reached
     * from the application layer only; refused once the code is used in an aggregate number.
     */
    fun changeEntityCode(
        tenant: TenantId,
        entityRef: String,
        code: String,
    ): StructureOutcome<EntityRecord> =
        tx.inTransaction {
            val entity = store.entity(entityRef) ?: return@inTransaction StructureOutcome.NotFound
            if (entity.code == code) return@inTransaction StructureOutcome.Done(entity, repeated = true)
            val newCode =
                when (val outcome = changedCode(entity.code, code)) {
                    is CodeOutcome.Refused -> return@inTransaction refused(outcome.reason)
                    is CodeOutcome.Accepted -> outcome.value
                }
            val version = store.nextStructureVersion()
            store.updateEntityCode(entity.ref, newCode.value, version)
            val updated = entity.copy(code = newCode.value, structureVersion = version)
            publisher.publish(
                change(
                    tenant,
                    entity.ref,
                    NodeType.ENTITY,
                    ChangeType.UPDATED,
                    entity.groupRef,
                    null,
                    entityNode(updated),
                ),
            )
            StructureOutcome.Done(updated)
        }

    private fun taken(): Set<ShortCode> = store.codes().map { it.code }.toSet()

    private fun changedCode(
        currentText: String,
        entered: String,
    ): CodeOutcome<ShortCode> {
        val uses = store.codes()
        val current = uses.first { it.code.value == currentText }
        return CodeRules.change(current.code, entered, uses.map { it.code }.toSet(), current.usedInAggregateNumber)
    }

    private fun refused(reason: CodeRefusal): StructureOutcome<Nothing> =
        if (reason == CodeRefusal.DUPLICATE || reason == CodeRefusal.USED_IN_AGGREGATE_NUMBER) {
            StructureOutcome.Conflict(reason)
        } else {
            StructureOutcome.Invalid(reason)
        }

    @Suppress("LongParameterList")
    private fun change(
        tenant: TenantId,
        entityRef: String,
        type: NodeType,
        changeType: ChangeType,
        parentRef: String?,
        previousParentRef: String?,
        node: StructureNodeState,
        nodeRef: String = entityRef,
    ) = StructureChange(tenant.value, entityRef, type, nodeRef, changeType, parentRef, previousParentRef, node)

    private companion object {
        const val DRAFT = "DRAFT"
    }
}

fun groupNode(
    group: GroupRecord,
    tenantRef: String,
) = StructureNodeState(
    NodeType.GROUP,
    tenantRef,
    group.retired,
    group.name,
    null,
    group.structureVersion,
    gstn = group.gstn,
)

fun entityNode(entity: EntityRecord) =
    StructureNodeState(
        NodeType.ENTITY,
        entity.groupRef,
        entity.retired,
        entity.legalName,
        entity.code,
        entity.structureVersion,
        entity.uen,
        entity.gstn,
    )

fun unitNode(unit: BusinessUnitRecord) =
    StructureNodeState(
        NodeType.BUSINESS_UNIT,
        unit.entityRef,
        unit.retired,
        unit.name,
        unit.code,
        unit.structureVersion,
    )
