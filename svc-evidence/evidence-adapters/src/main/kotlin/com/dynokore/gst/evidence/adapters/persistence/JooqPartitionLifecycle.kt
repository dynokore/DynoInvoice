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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The partition lifecycle of a retention-end deletion over the SECURITY DEFINER functions of V21 (least privilege: the
 * adapter only calls them), in the caller's tenant transaction, so a refusal of any call rolls the whole sequence back.
 * Each step runs once for each of the four chain tables in the order of [RecordKind] and, when the unit's registry has
 * a `prior_evidence` member (V24, the fifth member), once more for that table after them; a unit without it behaves as
 * before. A call that fails or is refused
 * stops the sequence and the failure is thrown, never skipped (INV-AC-22962, INV-AC-22964):
 * - [detach]: `detach_expired_partition(parent, partition, audit_ref, retention_end)` for each chain table;
 * - [archive]: the rows of each detached partition go to the object store, then `mark_partition_archived` is called
 *   once for the unit (the function checks all four members);
 * - [drop]: `drop_detached_partition(partition)` for each chain table.
 * The archive objects are locked for [archiveLockPeriod] from the write (NIST SP 800-53 AU-9); it is configuration with
 * no default: while it is null the deletion is refused before any object is written. Zero is allowed, negative is not.
 * [observe] is told the name of each call just before it is made (`detach:<table>`, `archive`, `drop:<table>`).
 */
class JooqPartitionLifecycle(
    private val dsl: DSLContext,
    private val store: ObjectStore,
    private val clock: Clock,
    private val archiveLockPeriod: Duration? = null,
    private val observe: (String) -> Unit = {},
) : PartitionLifecycle {
    init {
        require(archiveLockPeriod == null || !archiveLockPeriod.isNegative) {
            "the archive lock period must not be negative, was $archiveLockPeriod"
        }
    }

    override fun detach(
        partitionId: String,
        auditRef: String,
        retentionEnd: Instant,
    ) {
        checkNotNull(archiveLockPeriod) { "no archive lock period is configured" }
        // The runtime login holds no right on a detached partition (it is owned by the migration login), so the rows
        // are read through the chain tables, still attached, and written to the object store before the first detach.
        val prefix = archive(partitionId, readUnit(partitionId))
        pendingPrefix[partitionId] = prefix
        members(partitionId).forEach { table ->
            observe("detach:$table")
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "detach_expired_partition"),
                        SQLDataType.VARCHAR,
                        DSL.inline("$SCHEMA.$table"),
                        DSL.inline("$SCHEMA.${partitionTable(partitionId, table)}"),
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
        members(partitionId).forEach { table ->
            // The chain tables are ordered by seq; the prior table has none: transaction date, document number, id.
            val order =
                if (table == PRIOR_EVIDENCE) {
                    listOf("transaction_date", "document_number", "id").map { DSL.field(DSL.name(it)) }
                } else {
                    listOf(DSL.field(DSL.name("seq")))
                }
            val rows =
                dsl
                    .selectFrom(DSL.table(DSL.name(SCHEMA, table)))
                    .where(
                        DSL.field(DSL.name("tenant_id"), UUID::class.java).eq(unit.tenant),
                        DSL.field(DSL.name("entity_ref"), String::class.java).eq(unit.entity),
                        DSL.field(DSL.name("period_ref"), String::class.java).eq(unit.period),
                    ).orderBy(order)
                    .fetch()
            store.put(ObjectKey("$prefix/$table.json"), rows.formatJSON().toByteArray(Charsets.UTF_8), retain())
        }
        return prefix
    }

    private fun retain(): Instant = clock.instant().plus(checkNotNull(archiveLockPeriod))

    override fun drop(partitionId: String) {
        members(partitionId).forEach { table ->
            observe("drop:$table")
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "drop_detached_partition"),
                        SQLDataType.OTHER,
                        DSL.inline("$SCHEMA.${partitionTable(partitionId, table)}"),
                    ),
                ).fetch()
        }
    }

    /** The tables of the unit in call order: the four chain tables, then `prior_evidence` if the registry has it. */
    private fun members(partitionId: String): List<String> {
        val m = RETENTION_PARTITION_MEMBER
        val hasPrior =
            dsl.fetchExists(m, m.PARTITION_ID.eq(partitionId), m.CHAIN_TABLE.eq(PRIOR_EVIDENCE))
        return RecordKind.entries.map { it.table } + if (hasPrior) listOf(PRIOR_EVIDENCE) else emptyList()
    }

    /** The table of the unit's member [table], read from the registry; its shape is checked before it is inlined. */
    private fun partitionTable(
        partitionId: String,
        table: String,
    ): String {
        val m = RETENTION_PARTITION_MEMBER
        val name =
            dsl
                .select(m.PARTITION_TABLE)
                .from(m)
                .where(m.PARTITION_ID.eq(partitionId), m.CHAIN_TABLE.eq(table))
                .fetchOne(m.PARTITION_TABLE)
        require(name != null && TABLE_NAME.matches(name)) { "no chain partition of $partitionId in $table" }
        return name
    }

    /** The archive prefix of a unit between its detach and its archive mark (one deletion of a unit at a time). */
    private val pendingPrefix = ConcurrentHashMap<String, String>()

    private companion object {
        const val SCHEMA = "evidence"
        const val PRIOR_EVIDENCE = "prior_evidence"
        val TABLE_NAME = Regex("^[a-z_]+_rp_[0-9a-f]{32}$")
    }
}
