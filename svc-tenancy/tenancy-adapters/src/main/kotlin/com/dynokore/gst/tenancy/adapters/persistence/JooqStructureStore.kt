package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.BUSINESS_UNIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.structure.BusinessUnitRecord
import com.dynokore.gst.tenancy.application.structure.CodeUse
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupKind
import com.dynokore.gst.tenancy.application.structure.GroupRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.CodeOutcome
import com.dynokore.gst.tenancy.domain.ShortCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The tenancy tables of the bound tenant (jOOQ only, CS-18). The tenant column is written from the binding and every
 * read runs under forced row-level security, so a node of another tenant is simply absent.
 */
class JooqStructureStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : StructureStore {
    private fun tenant(): UUID = tenants.current().value

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    override fun group(ref: String): GroupRecord? =
        dsl.selectFrom(TENANT_GROUP).where(TENANT_GROUP.REF.eq(ref)).fetchOne()?.let(::toGroup)

    override fun groupByName(name: String): GroupRecord? =
        dsl.selectFrom(TENANT_GROUP).where(TENANT_GROUP.NAME.eq(name)).orderBy(TENANT_GROUP.CREATED_AT).limit(1).fetchOne()?.let(::toGroup)

    override fun insertGroup(group: GroupRecord) {
        dsl
            .insertInto(TENANT_GROUP)
            .set(TENANT_GROUP.ID, group.id)
            .set(TENANT_GROUP.TENANT_ID, tenant())
            .set(TENANT_GROUP.REF, group.ref)
            .set(TENANT_GROUP.NAME, group.name)
            .set(TENANT_GROUP.KIND, group.kind.name)
            .set(TENANT_GROUP.GSTN, group.gstn)
            .set(TENANT_GROUP.RETIRED, group.retired)
            .set(TENANT_GROUP.STRUCTURE_VERSION, group.structureVersion)
            .set(TENANT_GROUP.CREATED_AT, now())
            .execute()
    }

    override fun entity(ref: String): EntityRecord? = entityRows().where(ENTITY.REF.eq(ref)).fetchOne()?.let(::toEntity)

    override fun draftEntity(
        groupRef: String,
        legalName: String,
    ): EntityRecord? =
        entityRows()
            .where(TENANT_GROUP.REF.eq(groupRef), ENTITY.LEGAL_NAME.eq(legalName), ENTITY.LIFECYCLE_STATE.eq(DRAFT))
            .orderBy(ENTITY.CREATED_AT)
            .limit(1)
            .fetchOne()
            ?.let(::toEntity)

    override fun insertEntity(entity: EntityRecord) {
        val group = checkNotNull(group(entity.groupRef)) { "the group ${entity.groupRef} is not in the tenant" }
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, entity.id)
            .set(ENTITY.TENANT_ID, tenant())
            .set(ENTITY.REF, entity.ref)
            .set(ENTITY.GROUP_ID, group.id)
            .set(ENTITY.LEGAL_NAME, entity.legalName)
            .set(ENTITY.CONSTITUTION, entity.constitution)
            .set(ENTITY.UEN, entity.uen)
            .set(ENTITY.GSTN, entity.gstn)
            .set(ENTITY.CODE, entity.code)
            .set(ENTITY.LIFECYCLE_STATE, entity.lifecycleState)
            .set(ENTITY.RETIRED, entity.retired)
            .set(ENTITY.STRUCTURE_VERSION, entity.structureVersion)
            .set(ENTITY.CREATED_AT, now())
            .execute()
        insertCode(entity.code, ENTITY_KIND, entity.id)
    }

    override fun businessUnit(ref: String): BusinessUnitRecord? = unitRows().where(BUSINESS_UNIT.REF.eq(ref)).fetchOne()?.let(::toUnit)

    override fun businessUnitByCode(
        entityRef: String,
        code: String,
    ): BusinessUnitRecord? =
        unitRows().where(ENTITY.REF.eq(entityRef), BUSINESS_UNIT.CODE.eq(code)).fetchOne()?.let(::toUnit)

    override fun insertBusinessUnit(unit: BusinessUnitRecord) {
        val entity = checkNotNull(entity(unit.entityRef)) { "the entity ${unit.entityRef} is not in the tenant" }
        dsl
            .insertInto(BUSINESS_UNIT)
            .set(BUSINESS_UNIT.ID, unit.id)
            .set(BUSINESS_UNIT.TENANT_ID, tenant())
            .set(BUSINESS_UNIT.REF, unit.ref)
            .set(BUSINESS_UNIT.ENTITY_ID, entity.id)
            .set(BUSINESS_UNIT.NAME, unit.name)
            .set(BUSINESS_UNIT.CODE, unit.code)
            .set(BUSINESS_UNIT.RETIRED, unit.retired)
            .set(BUSINESS_UNIT.STRUCTURE_VERSION, unit.structureVersion)
            .set(BUSINESS_UNIT.CREATED_AT, now())
            .execute()
        insertCode(unit.code, UNIT_KIND, unit.id)
    }

    override fun moveBusinessUnit(
        ref: String,
        toEntityRef: String,
        structureVersion: Long,
    ) {
        val target = checkNotNull(entity(toEntityRef)) { "the entity $toEntityRef is not in the tenant" }
        dsl
            .update(BUSINESS_UNIT)
            .set(BUSINESS_UNIT.ENTITY_ID, target.id)
            .set(BUSINESS_UNIT.STRUCTURE_VERSION, structureVersion)
            .set(BUSINESS_UNIT.VERSION, BUSINESS_UNIT.VERSION.plus(1L))
            .where(BUSINESS_UNIT.REF.eq(ref))
            .execute()
    }

    override fun updateBusinessUnit(
        ref: String,
        name: String,
        code: String,
        structureVersion: Long,
    ) {
        val id = checkNotNull(dsl.select(BUSINESS_UNIT.ID).from(BUSINESS_UNIT).where(BUSINESS_UNIT.REF.eq(ref)).fetchOne()?.value1())
        dsl
            .update(BUSINESS_UNIT)
            .set(BUSINESS_UNIT.NAME, name)
            .set(BUSINESS_UNIT.CODE, code)
            .set(BUSINESS_UNIT.STRUCTURE_VERSION, structureVersion)
            .set(BUSINESS_UNIT.VERSION, BUSINESS_UNIT.VERSION.plus(1L))
            .where(BUSINESS_UNIT.REF.eq(ref))
            .execute()
        dsl.update(SHORT_CODE).set(SHORT_CODE.CODE, code).where(SHORT_CODE.NODE_ID.eq(id)).execute()
    }

    override fun updateEntityCode(
        ref: String,
        code: String,
        structureVersion: Long,
    ) {
        val id = checkNotNull(dsl.select(ENTITY.ID).from(ENTITY).where(ENTITY.REF.eq(ref)).fetchOne()?.value1())
        dsl
            .update(ENTITY)
            .set(ENTITY.CODE, code)
            .set(ENTITY.STRUCTURE_VERSION, structureVersion)
            .set(ENTITY.VERSION, ENTITY.VERSION.plus(1L))
            .where(ENTITY.REF.eq(ref))
            .execute()
        dsl.update(SHORT_CODE).set(SHORT_CODE.CODE, code).where(SHORT_CODE.NODE_ID.eq(id)).execute()
    }

    override fun codes(): List<CodeUse> =
        dsl.selectFrom(SHORT_CODE).fetch().map {
            val parsed = ShortCode.parse(it.code.column("code"))
            CodeUse(
                checkNotNull((parsed as? CodeOutcome.Accepted)?.value) { "a stored short code is malformed" },
                it.usedInAggregateNumber.column("used_in_aggregate_number"),
            )
        }

    override fun nextStructureVersion(): Long =
        dsl.select(DSL.sequence(DSL.name("tenancy", "structure_version_seq"), SQLDataType.BIGINT).nextval()).fetchSingle().value1().column("nextval")

    private fun insertCode(
        code: String,
        kind: String,
        node: UUID,
    ) {
        dsl
            .insertInto(SHORT_CODE)
            .set(SHORT_CODE.TENANT_ID, tenant())
            .set(SHORT_CODE.CODE, code)
            .set(SHORT_CODE.NODE_KIND, kind)
            .set(SHORT_CODE.NODE_ID, node)
            .execute()
    }

    private fun entityRows() = dsl.select(ENTITY.asterisk(), TENANT_GROUP.REF).from(ENTITY).join(TENANT_GROUP).on(TENANT_GROUP.ID.eq(ENTITY.GROUP_ID))

    private fun unitRows() = dsl.select(BUSINESS_UNIT.asterisk(), ENTITY.REF).from(BUSINESS_UNIT).join(ENTITY).on(ENTITY.ID.eq(BUSINESS_UNIT.ENTITY_ID))

    private fun toGroup(row: org.jooq.Record) =
        row.into(TENANT_GROUP).let {
            GroupRecord(
                it.id.column("id"),
                it.ref.column("ref"),
                it.name.column("name"),
                GroupKind.valueOf(it.kind.column("kind")),
                it.gstn,
                it.retired.column("retired"),
                it.structureVersion.column("structure_version"),
            )
        }

    private fun toEntity(row: org.jooq.Record) =
        row.into(ENTITY).let {
            EntityRecord(
                it.id.column("id"),
                it.ref.column("ref"),
                row.get(TENANT_GROUP.REF).column("group ref"),
                it.legalName.column("legal_name"),
                it.constitution.column("constitution"),
                it.uen,
                it.gstn,
                it.code.column("code"),
                it.lifecycleState.column("lifecycle_state"),
                it.retired.column("retired"),
                it.structureVersion.column("structure_version"),
            )
        }

    private fun toUnit(row: org.jooq.Record) =
        row.into(BUSINESS_UNIT).let {
            BusinessUnitRecord(
                it.id.column("id"),
                it.ref.column("ref"),
                row.get(ENTITY.REF).column("entity ref"),
                it.name.column("name"),
                it.code.column("code"),
                it.retired.column("retired"),
                it.structureVersion.column("structure_version"),
            )
        }

    private companion object {
        const val DRAFT = "DRAFT"
        const val ENTITY_KIND = "ENTITY"
        const val UNIT_KIND = "BUSINESS_UNIT"
    }
}
