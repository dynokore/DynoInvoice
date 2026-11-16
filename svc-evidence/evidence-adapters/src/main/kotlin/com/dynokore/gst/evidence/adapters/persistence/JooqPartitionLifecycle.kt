package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartition.Companion.RETENTION_PARTITION
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartitionMember.Companion.RETENTION_PARTITION_MEMBER
import com.dynokore.gst.evidence.application.retention.PartitionLifecycle
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The partition lifecycle of a retention-end deletion over the SECURITY DEFINER functions of V21 (least privilege: the
 * adapter only calls them), in the caller's tenant transaction, so a refusal of any call rolls the whole sequence back.
 * Each step runs once for each of the four chain tables in the order of [RecordKind]; a call that fails or is refused
 * stops the sequence and the failure is thrown, never skipped (INV-AC-22962, INV-AC-22964):
 * - [detach]: `detach_expired_partition(parent, partition, audit_ref, retention_end)` for each chain table;
 * - [archive]: the rows of each detached partition go to the object store, then `mark_partition_archived` is called
 *   once for the unit (the function checks all four members);
 * - [drop]: `drop_detached_partition(partition)` for each chain table.
 * [observe] is told the name of each call just before it is made (`detach:<table>`, `archive`, `drop:<table>`).
 */
class JooqPartitionLifecycle(
    private val dsl: DSLContext,
    private val store: ObjectStore,
    private val clock: Clock,
    private val observe: (String) -> Unit = {},
) : PartitionLifecycle {
    override fun detach(
        partitionId: String,
        auditRef: String,
        retentionEnd: Instant,
    ) {
        // The runtime login holds no right on a detached partition (it is owned by the migration login), so the rows
        // are read through the chain tables, still attached, and written to the object store before the first detach.
        val prefix = archive(partitionId, readUnit(partitionId))
        pendingPrefix[partitionId] = prefix
        RecordKind.entries.forEach { kind ->
            observe("detach:${kind.table}")
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "detach_expired_partition"),
                        SQLDataType.VARCHAR,
                        DSL.inline("$SCHEMA.${kind.table}"),
                        DSL.inline("$SCHEMA.${partitionTable(partitionId, kind)}"),
                        DSL.`val`(auditRef),
                        DSL.`val`(retentionEnd.atOffset(ZoneOffset.UTC)),
                    ),
                ).fetch()
        }
    }

    /** Marks the unit archived (`mark_partition_archived`, once; the function checks all four members detached). */
    override fun archive(partitionId: String): String {
        val prefix = checkNotNull(pendingPrefix.remove(partitionId)) { "the rows of $partitionId were not archived" }
        observe("archive")
        dsl
            .select(
                DSL.function(
                    DSL.name(SCHEMA, "mark_partition_archived"),
                    SQLDataType.OTHER,
                    DSL.`val`(partitionId),
                    DSL.`val`(prefix),
                ),
            ).fetch()
        return prefix
    }

    private class UnitKey(
        val tenant: UUID,
        val entity: String,
        val period: String,
    )

    private fun readUnit(partitionId: String): UnitKey {
        val p = RETENTION_PARTITION
        val row = dsl.selectFrom(p).where(p.PARTITION_ID.eq(partitionId)).fetchSingle()
        return UnitKey(row.tenantId.req(), row.entityRef.req(), row.periodFrom.req())
    }

    /** One object per chain table: the unit's rows as JSON, under `retention-archive/<tenant>/<partition>/`. */
    private fun archive(
        partitionId: String,
        unit: UnitKey,
    ): String {
        val prefix = "retention-archive/${unit.tenant}/$partitionId"
        RecordKind.entries.forEach { kind ->
            val rows =
                dsl
                    .selectFrom(DSL.table(DSL.name(SCHEMA, kind.table)))
                    .where(
                        DSL.field(DSL.name("tenant_id"), UUID::class.java).eq(unit.tenant),
                        DSL.field(DSL.name("entity_ref"), String::class.java).eq(unit.entity),
                        DSL.field(DSL.name("period_ref"), String::class.java).eq(unit.period),
                    ).orderBy(DSL.field(DSL.name("seq"), Long::class.java))
                    .fetch()
            store.put(ObjectKey("$prefix/${kind.table}.json"), rows.formatJSON().toByteArray(Charsets.UTF_8), retain())
        }
        return prefix
    }

    private fun retain(): Instant = clock.instant()

    override fun drop(partitionId: String) {
        RecordKind.entries.forEach { kind ->
            observe("drop:${kind.table}")
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "drop_detached_partition"),
                        SQLDataType.OTHER,
                        DSL.inline("$SCHEMA.${partitionTable(partitionId, kind)}"),
                    ),
                ).fetch()
        }
    }

    /** The table of the unit's member of [kind], read from the registry; its shape is checked before it is inlined. */
    private fun partitionTable(
        partitionId: String,
        kind: RecordKind,
    ): String {
        val m = RETENTION_PARTITION_MEMBER
        val name =
            dsl
                .select(m.PARTITION_TABLE)
                .from(m)
                .where(m.PARTITION_ID.eq(partitionId), m.CHAIN_TABLE.eq(kind.table))
                .fetchOne(m.PARTITION_TABLE)
        require(name != null && TABLE_NAME.matches(name)) { "no chain partition of $partitionId in ${kind.table}" }
        return name
    }

    /** The archive prefix of a unit between its detach and its archive mark (one deletion of a unit at a time). */
    private val pendingPrefix = ConcurrentHashMap<String, String>()

    private companion object {
        const val SCHEMA = "evidence"
        val TABLE_NAME = Regex("^[a-z_]+_rp_[0-9a-f]{32}$")
    }
}
