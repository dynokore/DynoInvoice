package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.BUSINESS_UNIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.structure.BusinessUnitRecord
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupKind
import com.dynokore.gst.tenancy.application.structure.GroupRecord
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.application.structure.groupNode
import com.dynokore.gst.tenancy.application.structure.unitNode
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One node of the bound tenant as a replay re-emits it (INV-Q-11719 A). */
data class ReplayNode(
    val type: NodeType,
    val ref: String,
    /** The EntityRef the live event of this node carries: the group's ref, the entity's ref, a unit's entity ref. */
    val entityRef: String,
    /** The node exactly as getEntityStructure answers it, profile included. */
    val node: StructureNodeState,
    /** The effective_from of the entry with the greatest recorded_at (ties: the greater effective_from), if any. */
    val profileEffectiveDate: LocalDate?,
)

/** The position after a state: the kind and ref of the last state returned (nodes are ordered by kind, then ref). */
data class ReplayCursor(
    val type: NodeType,
    val ref: String,
) {
    fun encode(): String = "${type.name}$SEPARATOR$ref"

    companion object {
        private const val SEPARATOR = '|'
        private val KINDS = listOf(NodeType.GROUP, NodeType.ENTITY, NodeType.BUSINESS_UNIT)

        fun decode(text: String): ReplayCursor {
            val at = text.indexOf(SEPARATOR)
            require(at > 0 && at < text.length - 1) { "malformed replay cursor" }
            val type = KINDS.firstOrNull { it.name == text.substring(0, at) }
            return ReplayCursor(requireNotNull(type) { "malformed replay cursor" }, text.substring(at + 1))
        }
    }
}

/** The current state of the bound tenant for the replay sources; never the outbox or the event log. */
interface ReplayReader {
    /** Every tenant that holds state, outside any tenant binding. */
    fun tenantIds(): List<TenantId>

    /** Up to [limit] groups, entities and business units within [scope] strictly after [after], in a stable order. */
    fun structure(
        scope: ReplayScope?,
        after: ReplayCursor?,
        limit: Int,
    ): List<ReplayNode>

    /** Up to [limit] entities that have at least one status entry, within [scope], strictly after [after]. */
    fun profiles(
        scope: ReplayScope?,
        after: ReplayCursor?,
        limit: Int,
    ): List<ReplayNode>
}

/**
 * The replay reads over the tenancy tables (jOOQ only, CS-18). [dsl] is the tenant-bound context: forced row-level
 * security shows only the bound tenant's rows, so no read here names the tenant. The tenant listing is the one
 * installation-level read, through the definer function `tenancy.list_tenant_ids()` (INV-Q-11717 A).
 */
class JooqReplayReader(
    private val dsl: DSLContext,
    tenants: TenantContext,
) : ReplayReader {
    private val statuses = JooqEntityStatusStore(dsl, tenants)

    override fun tenantIds(): List<TenantId> =
        dsl
            .select(DSL.function(DSL.name("tenancy", "list_tenant_ids"), SQLDataType.UUID))
            .fetch()
            .map { TenantId(it.value1().column("tenant_id")) }

    override fun structure(
        scope: ReplayScope?,
        after: ReplayCursor?,
        limit: Int,
    ): List<ReplayNode> {
        val out = ArrayList<ReplayNode>()
        for (type in KINDS.drop(after?.let { KINDS.indexOf(it.type) } ?: 0)) {
            val remaining = limit - out.size
            val cursorRef = after?.takeIf { it.type == type }?.ref
            if (remaining > 0) out += of(type, scope, cursorRef, remaining)
        }
        return out
    }

    override fun profiles(
        scope: ReplayScope?,
        after: ReplayCursor?,
        limit: Int,
    ): List<ReplayNode> =
        if (after != null && after.type != NodeType.ENTITY) {
            emptyList()
        } else {
            entities(scope, after?.ref, limit, withStatusOnly = true)
        }

    private fun of(
        type: NodeType,
        scope: ReplayScope?,
        cursorRef: String?,
        limit: Int,
    ): List<ReplayNode> =
        when (type) {
            NodeType.GROUP -> groups(scope, cursorRef, limit)
            NodeType.ENTITY -> entities(scope, cursorRef, limit, withStatusOnly = false)
            else -> units(scope, cursorRef, limit)
        }

    private fun groups(
        scope: ReplayScope?,
        afterRef: String?,
        limit: Int,
    ): List<ReplayNode> {
        val tenantRef =
            dsl
                .select(TENANT.REF)
                .from(TENANT)
                .fetchOne()
                ?.value1() ?: return emptyList()
        return dsl
            .selectFrom(TENANT_GROUP)
            .where(
                ReplayConditions.all(
                    ReplayConditions.inScope(scope, TENANT_GROUP.REF, TENANT_GROUP.CHANGED_AT),
                    afterRef?.let { TENANT_GROUP.REF.gt(it) },
                ),
            ).orderBy(TENANT_GROUP.REF)
            .limit(limit)
            .fetch()
            .map {
                val group =
                    GroupRecord(
                        it.id.column("id"),
                        it.ref.column("ref"),
                        it.name.column("name"),
                        GroupKind.valueOf(it.kind.column("kind")),
                        it.gstn,
                        it.retired.column("retired"),
                        it.structureVersion.column("structure_version"),
                    )
                ReplayNode(NodeType.GROUP, group.ref, group.ref, groupNode(group, tenantRef), null)
            }
    }

    private fun entities(
        scope: ReplayScope?,
        afterRef: String?,
        limit: Int,
        withStatusOnly: Boolean,
    ): List<ReplayNode> {
        val hasStatus = DSL.exists(DSL.selectOne().from(ENTITY_STATUS).where(ENTITY_STATUS.ENTITY_ID.eq(ENTITY.ID)))
        val rows =
            dsl
                .select(ENTITY.asterisk(), TENANT_GROUP.REF)
                .from(ENTITY)
                .join(TENANT_GROUP)
                .on(TENANT_GROUP.ID.eq(ENTITY.GROUP_ID))
                .where(
                    ReplayConditions.all(
                        ReplayConditions.inScope(scope, ENTITY.REF, ENTITY.CHANGED_AT),
                        afterRef?.let { ENTITY.REF.gt(it) },
                        if (withStatusOnly) hasStatus else null,
                    ),
                ).orderBy(ENTITY.REF)
                .limit(limit)
                .fetch()
        val greatest = greatestRecorded(rows.map { it.get(ENTITY.ID).column("id") })
        return rows.map { row ->
            val e = row.into(ENTITY)
            val entity =
                EntityRecord(
                    e.id.column("id"),
                    e.ref.column("ref"),
                    row.get(TENANT_GROUP.REF).column("group ref"),
                    e.legalName.column("legal_name"),
                    e.constitution.column("constitution"),
                    e.uen,
                    e.gstn,
                    e.code.column("code"),
                    e.lifecycleState.column("lifecycle_state"),
                    e.retired.column("retired"),
                    e.structureVersion.column("structure_version"),
                )
            val profile = statuses.history(entity.id).map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) }
            ReplayNode(NodeType.ENTITY, entity.ref, entity.ref, entityNode(entity, profile), greatest[entity.id])
        }
    }

    private fun units(
        scope: ReplayScope?,
        afterRef: String?,
        limit: Int,
    ): List<ReplayNode> =
        dsl
            .select(BUSINESS_UNIT.asterisk(), ENTITY.REF)
            .from(BUSINESS_UNIT)
            .join(ENTITY)
            .on(ENTITY.ID.eq(BUSINESS_UNIT.ENTITY_ID))
            .where(ReplayConditions.all(ReplayConditions.unitScope(scope), afterRef?.let { BUSINESS_UNIT.REF.gt(it) }))
            .orderBy(BUSINESS_UNIT.REF)
            .limit(limit)
            .fetch()
            .map { row ->
                val u = row.into(BUSINESS_UNIT)
                val unit =
                    BusinessUnitRecord(
                        u.id.column("id"),
                        u.ref.column("ref"),
                        row.get(ENTITY.REF).column("entity ref"),
                        u.name.column("name"),
                        u.code.column("code"),
                        u.retired.column("retired"),
                        u.structureVersion.column("structure_version"),
                    )
                ReplayNode(NodeType.BUSINESS_UNIT, unit.ref, unit.entityRef, unitNode(unit), null)
            }

    /** For each entity, the effective_from of the status entry with the greatest recorded_at (ties: greater date). */
    private fun greatestRecorded(entityIds: List<UUID>): Map<UUID, LocalDate> =
        if (entityIds.isEmpty()) {
            emptyMap()
        } else {
            dsl
                .select(ENTITY_STATUS.ENTITY_ID, ENTITY_STATUS.EFFECTIVE_FROM, ENTITY_STATUS.RECORDED_AT)
                .from(ENTITY_STATUS)
                .where(ENTITY_STATUS.ENTITY_ID.`in`(entityIds))
                .fetch()
                .groupBy { it.value1().column("entity_id") }
                .mapValues { (_, entries) ->
                    entries
                        .maxWith(
                            compareBy<org.jooq.Record3<UUID?, LocalDate?, OffsetDateTime?>> { it.value3() }
                                .thenBy { it.value2() },
                        ).value2()
                        .column("effective_from")
                }
        }

    private companion object {
        val KINDS = listOf(NodeType.GROUP, NodeType.ENTITY, NodeType.BUSINESS_UNIT)
    }
}

/** The jOOQ conditions of a replay scope and of a cursor over the tenancy tables. */
private object ReplayConditions {
    /** The scope of a group or entity read: [refField] is the EntityRef of the node, [changed] its last change. */
    fun inScope(
        scope: ReplayScope?,
        refField: Field<String?>,
        changed: Field<OffsetDateTime?>,
    ): Condition =
        when (scope) {
            null, is ReplayScope.Tenant -> DSL.trueCondition()
            is ReplayScope.Entity -> refField.eq(scope.entity.value)
            is ReplayScope.Period -> period(scope, changed)
        }

    /** A business unit's EntityRef is its entity's ref; its PERIOD is its own change. */
    fun unitScope(scope: ReplayScope?): Condition =
        when (scope) {
            null, is ReplayScope.Tenant -> DSL.trueCondition()
            is ReplayScope.Entity -> ENTITY.REF.eq(scope.entity.value)
            is ReplayScope.Period -> period(scope, BUSINESS_UNIT.CHANGED_AT)
        }

    fun period(
        scope: ReplayScope.Period,
        changed: Field<OffsetDateTime?>,
    ): Condition = changed.ge(utc(scope.from)).and(changed.lt(utc(scope.to)))

    fun all(vararg conditions: Condition?): Condition = DSL.and(conditions.filterNotNull())

    fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
}
