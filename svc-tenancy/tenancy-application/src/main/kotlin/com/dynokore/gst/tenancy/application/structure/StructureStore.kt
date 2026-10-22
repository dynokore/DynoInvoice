package com.dynokore.gst.tenancy.application.structure

import com.dynokore.gst.tenancy.domain.ShortCode
import java.util.UUID

enum class GroupKind { GST_GROUP, DIVISIONAL }

data class GroupRecord(
    val id: UUID,
    val ref: String,
    val name: String,
    val kind: GroupKind,
    val gstn: String?,
    val retired: Boolean,
    val structureVersion: Long,
)

data class EntityRecord(
    val id: UUID,
    val ref: String,
    val groupRef: String,
    val legalName: String,
    val constitution: String,
    val uen: String?,
    val gstn: String?,
    val code: String,
    val lifecycleState: String,
    val retired: Boolean,
    val structureVersion: Long,
)

data class BusinessUnitRecord(
    val id: UUID,
    val ref: String,
    val entityRef: String,
    val name: String,
    val code: String,
    val retired: Boolean,
    val structureVersion: Long,
)

/** A short code of the tenant and whether an aggregate number already uses it (INV-REQ-436). */
data class CodeUse(
    val code: ShortCode,
    val usedInAggregateNumber: Boolean,
)

/** The groups of the bound tenant. Row-level security shows only that tenant's rows; every method runs in the
 * caller's transaction. */
interface GroupStore {
    fun group(ref: String): GroupRecord?

    fun groupByName(name: String): GroupRecord?

    fun insertGroup(group: GroupRecord)
}

/** The registered entities of the bound tenant. */
interface EntityStore {
    fun entity(ref: String): EntityRecord?

    fun draftEntity(
        groupRef: String,
        legalName: String,
    ): EntityRecord?

    fun insertEntity(entity: EntityRecord)

    fun updateEntityCode(
        ref: String,
        code: String,
        structureVersion: Long,
    )

    /** Locks the entity's row and stamps the structure version a profile change drew (TPL-05, INV-API-3327). */
    fun touchEntity(
        ref: String,
        structureVersion: Long,
    )
}

/** The business units of the bound tenant. */
interface BusinessUnitStore {
    fun businessUnit(ref: String): BusinessUnitRecord?

    fun businessUnitByCode(
        entityRef: String,
        code: String,
    ): BusinessUnitRecord?

    fun insertBusinessUnit(unit: BusinessUnitRecord)

    fun moveBusinessUnit(
        ref: String,
        toEntityRef: String,
        structureVersion: Long,
    )

    fun updateBusinessUnit(
        ref: String,
        name: String,
        code: String,
        structureVersion: Long,
    )
}

/** The short-code register and the one structure-version sequence of the bound tenant. */
interface CodeRegister {
    fun codes(): List<CodeUse>

    /** The next value of the one structure-version sequence (INV-API-3327), taken in the changing transaction. */
    fun nextStructureVersion(): Long
}

/** The tenancy store of one bound tenant: a node of another tenant is simply absent. */
interface StructureStore :
    GroupStore,
    EntityStore,
    BusinessUnitStore,
    CodeRegister
