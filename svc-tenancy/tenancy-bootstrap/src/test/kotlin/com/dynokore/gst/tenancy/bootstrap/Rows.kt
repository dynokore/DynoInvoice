package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.BUSINESS_UNIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.REGISTERED_ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The ids of one tenant's four levels, written by [Rows.structure]. */
data class Structure(
    val tenant: TenantId,
    val group: UUID,
    val entity: UUID,
    val businessUnit: UUID,
)

/**
 * Test rows written straight through jOOQ under a bound tenant (the use cases of createTenant, createEntity and the
 * group and business-unit operations wait for INV-T-7381 and the gap rulings; the storage-layer criteria do not).
 */
object Rows {
    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    /** One tenant, a group, an entity under it and a business unit under the entity; run in a bound transaction. */
    fun structure(
        dsl: DSLContext,
        tenant: TenantId,
        suffix: String,
        code: String,
    ): Structure {
        val group = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val tenantId = tenant.value
        dsl
            .insertInto(TENANT)
            .set(TENANT.TENANT_ID, tenantId)
            .set(TENANT.REF, "tenant-$suffix")
            .set(TENANT.LEGAL_NAME, "Tenant $suffix")
            .set(TENANT.PLAN_REF, "plan-$suffix")
            .set(TENANT.CONTRACT_REFERENCE, "contract-$suffix")
            .set(TENANT.STRUCTURE_VERSION, 1L)
            .set(TENANT.CREATED_AT, now())
            .execute()
        dsl
            .insertInto(TENANT_GROUP)
            .set(TENANT_GROUP.ID, group)
            .set(TENANT_GROUP.TENANT_ID, tenantId)
            .set(TENANT_GROUP.REF, "group-$suffix")
            .set(TENANT_GROUP.NAME, "Group $suffix")
            .set(TENANT_GROUP.KIND, "GST_GROUP")
            .set(TENANT_GROUP.GSTN, "M2-0000001-$suffix".take(20))
            .set(TENANT_GROUP.STRUCTURE_VERSION, 1L)
            .set(TENANT_GROUP.CREATED_AT, now())
            .execute()
        entity(dsl, tenant, group, entity, "entity-$suffix", code, "LIVE", LocalDate.of(2028, 4, 1))
        dsl
            .insertInto(BUSINESS_UNIT)
            .set(BUSINESS_UNIT.ID, unit)
            .set(BUSINESS_UNIT.TENANT_ID, tenantId)
            .set(BUSINESS_UNIT.REF, "unit-$suffix")
            .set(BUSINESS_UNIT.ENTITY_ID, entity)
            .set(BUSINESS_UNIT.NAME, "Unit $suffix")
            .set(BUSINESS_UNIT.CODE, "${code}U")
            .set(BUSINESS_UNIT.STRUCTURE_VERSION, 2L)
            .set(BUSINESS_UNIT.CREATED_AT, now())
            .execute()
        shortCode(dsl, tenantId, "${code}U", "BUSINESS_UNIT", unit)
        return Structure(tenant, group, entity, unit)
    }

    @Suppress("LongParameterList")
    fun entity(
        dsl: DSLContext,
        tenant: TenantId,
        group: UUID,
        id: UUID,
        ref: String,
        code: String,
        state: String,
        goLive: LocalDate?,
    ) {
        val tenantId = tenant.value
        dsl
            .insertInto(REGISTERED_ENTITY)
            .set(REGISTERED_ENTITY.ID, id)
            .set(REGISTERED_ENTITY.TENANT_ID, tenantId)
            .set(REGISTERED_ENTITY.REF, ref)
            .set(REGISTERED_ENTITY.GROUP_ID, group)
            .set(REGISTERED_ENTITY.LEGAL_NAME, "Entity $ref")
            .set(REGISTERED_ENTITY.CONSTITUTION, "COMPANY")
            .set(REGISTERED_ENTITY.UEN, "UEN-$code")
            .set(REGISTERED_ENTITY.GSTN, null as String?)
            .set(REGISTERED_ENTITY.CODE, code)
            .set(REGISTERED_ENTITY.LIFECYCLE_STATE, state)
            .set(REGISTERED_ENTITY.GO_LIVE_DATE, goLive)
            .set(REGISTERED_ENTITY.STRUCTURE_VERSION, 1L)
            .set(REGISTERED_ENTITY.CREATED_AT, now())
            .execute()
        shortCode(dsl, tenantId, code, "ENTITY", id)
    }

    private fun shortCode(
        dsl: DSLContext,
        tenantId: UUID,
        code: String,
        kind: String,
        node: UUID,
    ) {
        dsl
            .insertInto(SHORT_CODE)
            .set(SHORT_CODE.TENANT_ID, tenantId)
            .set(SHORT_CODE.CODE, code)
            .set(SHORT_CODE.NODE_KIND, kind)
            .set(SHORT_CODE.NODE_ID, node)
            .execute()
    }
}
