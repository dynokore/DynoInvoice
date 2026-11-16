package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Acknowledgement.Companion.ACKNOWLEDGEMENT
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AuditLog.Companion.AUDIT_LOG
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DecisionTrace.Companion.DECISION_TRACE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidencePayload.Companion.EVIDENCE_PAYLOAD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityFailure.Companion.INTEGRITY_FAILURE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityRun.Companion.INTEGRITY_RUN
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.LegalHold.Companion.LEGAL_HOLD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PartitionDeletionOperation.Companion.PARTITION_DELETION_OPERATION
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionApproval.Companion.RETENTION_APPROVAL
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionNotice.Companion.RETENTION_NOTICE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartition.Companion.RETENTION_PARTITION
import com.dynokore.gst.evidence.application.retention.Approval
import com.dynokore.gst.evidence.application.retention.ApprovalReader
import com.dynokore.gst.evidence.application.retention.DeletionAudit
import com.dynokore.gst.evidence.application.retention.DeletionIntegrity
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.application.retention.HoldReader
import com.dynokore.gst.evidence.application.retention.NoticeReader
import com.dynokore.gst.evidence.application.retention.NoticeRecord
import com.dynokore.gst.evidence.application.retention.PartitionRecord
import com.dynokore.gst.evidence.application.retention.PartitionRegistry
import com.dynokore.gst.evidence.application.retention.PartitionState
import com.dynokore.gst.evidence.application.retention.RetentionEndReader
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.gst.evidence.domain.retention.HoldStatus
import com.dynokore.gst.evidence.domain.retention.LegalHold
import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import com.dynokore.gst.evidence.domain.retention.RetentionTier
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * How the `period_ref` text of a unit is read as a date range (the parse of the retention end and of the holds): only
 * `YYYY-Qn` (a quarter), `YYYY-MM` (a month), `YYYY-MM-DD` (a day) and `YYYY` (a year) are read; every other text is
 * not a period this service can date, and gives null.
 */
object PeriodText {
    private val quarter = Regex("""^(\d{4})-Q([1-4])$""")
    private val month = Regex("""^(\d{4})-(0[1-9]|1[0-2])$""")
    private val day = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private const val MONTHS_PER_QUARTER = 3
    private val year = Regex("""^\d{4}$""")

    /** The first and the last day of the period, or null when the text is not a period. */
    fun range(text: String): Pair<LocalDate, LocalDate>? {
        val q = quarter.matchEntire(text)
        val m = month.matchEntire(text)
        return try {
            when {
                q != null -> {
                    val first =
                        LocalDate.of(
                            q.groupValues[1].toInt(),
                            (q.groupValues[2].toInt() - 1) * MONTHS_PER_QUARTER + 1,
                            1,
                        )
                    first to first.plusMonths(MONTHS_PER_QUARTER.toLong()).minusDays(1)
                }

                m != null -> {
                    val first = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1)
                    first to first.plusMonths(1).minusDays(1)
                }

                day.matches(text) -> {
                    LocalDate.parse(text).let { it to it }
                }

                year.matches(text) -> {
                    LocalDate.of(text.toInt(), 1, 1).let { it to it.plusYears(1).minusDays(1) }
                }

                else -> {
                    null
                }
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * The readers of the deletion use case over `retention_partition`, `legal_hold`, `retention_notice`,
 * `retention_approval`, the chain tables and the integrity tables, on the request's tenant-bound [DSLContext] (jOOQ
 * only, STK §3; INV-REQ-639). A tenant text that is not a UUID reads nothing (fail closed).
 */
@Suppress("TooManyFunctions") // one reader per port of the use case
class JooqRetentionRecords(
    private val dsl: DSLContext,
    private val effective: EffectiveRetention,
    private val clock: Clock,
) : PartitionRegistry,
    HoldReader,
    RetentionEndReader,
    NoticeReader,
    ApprovalReader,
    DeletionIntegrity {
    private val p = RETENTION_PARTITION

    /** The columns of one chain table that the tail hash reads. */
    private class Chain(
        val tenant: Field<UUID?>,
        val entity: Field<String?>,
        val period: Field<String?>,
        val seq: Field<Long?>,
        val hash: Field<String?>,
        val table: Table<*>,
    )

    private val chains: Map<RecordKind, Chain> =
        mapOf(
            RecordKind.DECISION_TRACE to
                Chain(
                    DECISION_TRACE.TENANT_ID,
                    DECISION_TRACE.ENTITY_REF,
                    DECISION_TRACE.PERIOD_REF,
                    DECISION_TRACE.SEQ,
                    DECISION_TRACE.CONTENT_HASH,
                    DECISION_TRACE,
                ),
            RecordKind.EVIDENCE_PAYLOAD to
                Chain(
                    EVIDENCE_PAYLOAD.TENANT_ID,
                    EVIDENCE_PAYLOAD.ENTITY_REF,
                    EVIDENCE_PAYLOAD.PERIOD_REF,
                    EVIDENCE_PAYLOAD.SEQ,
                    EVIDENCE_PAYLOAD.CONTENT_HASH,
                    EVIDENCE_PAYLOAD,
                ),
            RecordKind.ACKNOWLEDGEMENT to
                Chain(
                    ACKNOWLEDGEMENT.TENANT_ID,
                    ACKNOWLEDGEMENT.ENTITY_REF,
                    ACKNOWLEDGEMENT.PERIOD_REF,
                    ACKNOWLEDGEMENT.SEQ,
                    ACKNOWLEDGEMENT.CONTENT_HASH,
                    ACKNOWLEDGEMENT,
                ),
            RecordKind.AUDIT_LOG to
                Chain(
                    AUDIT_LOG.TENANT_ID,
                    AUDIT_LOG.ENTITY_REF,
                    AUDIT_LOG.PERIOD_REF,
                    AUDIT_LOG.SEQ,
                    AUDIT_LOG.CONTENT_HASH,
                    AUDIT_LOG,
                ),
        )

    override fun find(partitionId: String): PartitionRecord? {
        val row = dsl.selectFrom(p).where(p.PARTITION_ID.eq(partitionId)).fetchOne() ?: return null
        val tenant = checkNotNull(row.tenantId)
        val dropped = row.state == "DROPPED"
        val range = PeriodText.range(row.periodFrom.req())
        return PartitionRecord(
            partitionId = partitionId,
            entityRef = row.entityRef.req(),
            periodFrom = range?.first ?: LocalDate.MIN,
            periodTo = range?.second ?: LocalDate.MAX,
            state = if (dropped) PartitionState.DROPPED else PartitionState.ACTIVE,
            tailHashes = if (dropped) emptyList() else tailHashes(tenant, row.entityRef.req(), row.periodFrom.req()),
        )
    }

    /** The newest content hash of the unit in each chain table, `table=hash`, in the order of [RecordKind]. */
    private fun tailHashes(
        tenant: UUID,
        entity: String,
        period: String,
    ): List<String> =
        RecordKind.entries.mapNotNull { kind ->
            val c = chains.getValue(kind)
            dsl
                .select(c.hash)
                .from(c.table)
                .where(c.tenant.eq(tenant), c.entity.eq(entity), c.period.eq(period))
                .orderBy(c.seq.desc())
                .limit(1)
                .fetchOne(c.hash)
                ?.let { "${kind.table}=$it" }
        }

    override fun activeCovering(
        tenant: String,
        entity: String,
        periodFrom: LocalDate,
        periodTo: LocalDate,
    ): List<LegalHold> {
        val id = uuid(tenant) ?: return emptyList()
        return dsl
            .selectFrom(LEGAL_HOLD)
            .where(
                LEGAL_HOLD.TENANT_ID.eq(id),
                LEGAL_HOLD.ENTITY_REF.eq(entity),
                LEGAL_HOLD.STATUS.eq("ACTIVE"),
            ).fetch()
            .map {
                // a hold period this service cannot date is read as covering the partition: the stop is the safe side
                val date = PeriodText.range(it.periodRef.req())?.first ?: periodFrom
                LegalHold(
                    checkNotNull(it.holdId).toString(),
                    it.entityRef.req(),
                    date,
                    it.reason.req(),
                    HoldStatus.ACTIVE,
                )
            }
    }

    /**
     * The end of the period range plus the greatest years of the three tiers (the longest: a shorter retention is the
     * unsafe direction, INV-Q-12043), or null when the period cannot be dated or any read fails.
     */
    override fun retentionEnd(
        tenant: String,
        partitionId: String,
    ): Instant? {
        val id = uuid(tenant)
        return if (id == null) null else endOf(id, partitionId)
    }

    @Suppress("TooGenericExceptionCaught") // any failed read refuses the deletion: the end is then unknown
    private fun endOf(
        id: UUID,
        partitionId: String,
    ): Instant? =
        try {
            val row = dsl.selectFrom(p).where(p.PARTITION_ID.eq(partitionId), p.TENANT_ID.eq(id)).fetchOne()
            val last = row?.let { PeriodText.range(it.periodFrom.req())?.second }
            if (row == null || last == null) {
                null
            } else {
                val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                val years = RetentionTier.entries.maxOf { effective.years(row.entityRef.req(), it, today) }
                last
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .plusYears(years.toLong())
                    .toInstant()
            }
        } catch (_: Exception) {
            null
        }

    override fun window(
        tenant: String,
        partitionId: String,
    ): NoticeRecord? {
        val id = uuid(tenant) ?: return null
        val n = RETENTION_NOTICE
        return dsl
            .selectFrom(n)
            .where(n.TENANT_ID.eq(id), n.PARTITION_ID.eq(partitionId))
            .orderBy(n.NOTIFIED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.let { NoticeRecord(it.notifiedAt.req().toInstant(), NoticeWindow(maxOf(it.windowDays.req(), 1))) }
    }

    override fun approvals(
        tenant: String,
        partitionId: String,
    ): List<Approval> {
        val id = uuid(tenant) ?: return emptyList()
        val a = RETENTION_APPROVAL
        return dsl
            .selectFrom(a)
            .where(a.TENANT_ID.eq(id), a.PARTITION_ID.eq(partitionId))
            .fetch()
            .map { Approval(it.approverUserId.req(), it.permissionKey.req()) }
    }

    /** No open integrity failure of the unit and no latest FAILED run: the same facts the database function reads. */
    override fun allowsDeletion(
        tenant: String,
        partitionId: String,
    ): Boolean {
        val id = uuid(tenant)
        val unit = id?.let { dsl.selectFrom(p).where(p.PARTITION_ID.eq(partitionId), p.TENANT_ID.eq(id)).fetchOne() }
        return id != null && unit != null && noFailure(id, unit.entityRef.req(), unit.periodFrom.req())
    }

    private fun noFailure(
        id: UUID,
        entity: String,
        period: String,
    ): Boolean {
        val f = INTEGRITY_FAILURE
        val failed =
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(f)
                    .where(f.TENANT_ID.eq(id), f.ENTITY_REF.eq(entity), f.PERIOD_REF.eq(period)),
            )
        val r = INTEGRITY_RUN
        val latest =
            dsl
                .select(r.RESULT)
                .from(r)
                .where(r.TENANT_ID.eq(id), r.ENTITY_REF.eq(entity), r.PERIOD_REF.eq(period))
                .orderBy(r.RUN_AT.desc(), r.SEQ.desc())
                .limit(1)
                .fetchOne(r.RESULT)
        return !failed && latest != RunResult.FAILED.name
    }

    private fun uuid(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
}

/** A NOT NULL column of a generated record, which jOOQ types as nullable. */
internal fun <T : Any> T?.req(): T = checkNotNull(this) { "a NOT NULL column was read as null" }

/** One row of `partition_deletion_operation`. */
data class DeletionOperation(
    val operationId: UUID,
    val partitionId: String,
    val starterUserId: String,
    val state: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val errorCode: String?,
)

/**
 * `partition_deletion_operation` over the tenant-bound [DSLContext]: the status resource of a deletion and its
 * single-flight claim (the partial unique index of V22 holds a partition while an operation is RUNNING or SUCCEEDED).
 * The starter is the acting user ID, never a role.
 */
class JooqPartitionDeletionOperations(
    private val dsl: DSLContext,
) {
    private val t = PARTITION_DELETION_OPERATION

    /** Inserts the RUNNING row; null when the claim is held (a RUNNING or SUCCEEDED row of the partition exists). */
    fun claim(
        tenant: TenantId,
        partitionId: String,
        starterUserId: String,
        now: Instant,
    ): DeletionOperation? {
        val id = UUID.randomUUID()
        val inserted =
            dsl
                .insertInto(t)
                .set(t.OPERATION_ID, id)
                .set(t.TENANT_ID, tenant.value)
                .set(t.PARTITION_ID, partitionId)
                .set(t.STARTER_USER_ID, starterUserId)
                .set(t.STATE, RUNNING)
                .set(t.STARTED_AT, now.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute()
        return if (inserted == 1) {
            DeletionOperation(id, partitionId, starterUserId, RUNNING, now, null, null)
        } else {
            null
        }
    }

    /** Ends the RUNNING row of [operationId]: SUCCEEDED, or FAILED with [errorCode] (at most 100 characters). */
    fun finish(
        tenant: TenantId,
        operationId: UUID,
        succeeded: Boolean,
        errorCode: String?,
        now: Instant,
    ) {
        val changed =
            dsl
                .update(t)
                .set(t.STATE, if (succeeded) SUCCEEDED else FAILED)
                .set(t.COMPLETED_AT, now.atOffset(ZoneOffset.UTC))
                .set(t.ERROR_CODE, errorCode?.take(MAX_ERROR_CODE))
                .where(t.TENANT_ID.eq(tenant.value), t.OPERATION_ID.eq(operationId), t.STATE.eq(RUNNING))
                .execute()
        check(changed == 1) { "the deletion operation $operationId is not RUNNING" }
    }

    /** The latest operation of the partition by `started_at`, or null. */
    fun latest(
        tenant: TenantId,
        partitionId: String,
    ): DeletionOperation? =
        dsl
            .selectFrom(t)
            .where(t.TENANT_ID.eq(tenant.value), t.PARTITION_ID.eq(partitionId))
            .orderBy(t.STARTED_AT.desc(), t.OPERATION_ID.desc())
            .limit(1)
            .fetchOne()
            ?.let {
                DeletionOperation(
                    checkNotNull(it.operationId),
                    it.partitionId.req(),
                    it.starterUserId.req(),
                    it.state.req(),
                    it.startedAt.req().toInstant(),
                    it.completedAt?.toInstant(),
                    it.errorCode,
                )
            }

    companion object {
        const val RUNNING = "RUNNING"
        const val SUCCEEDED = "SUCCEEDED"
        const val FAILED = "FAILED"
        private const val MAX_ERROR_CODE = 100
    }
}

/**
 * The deletion audit entry (NIST SP 800-53 AU-2), written through the platform audit writer in the open deletion
 * transaction: it commits with the deletion or vanishes with it. The actor is the user ID; the prior state holds the
 * chain tail hashes of the partition. The reference returned is the id of the entry's event.
 */
class OutboxDeletionAudit(
    private val writer: AuditWriter,
) : DeletionAudit {
    private val mapper = ObjectMapper()

    override fun append(
        tenant: String,
        partitionId: String,
        actorUserId: String,
        tailHashes: List<String>,
    ): String {
        val tenantId = TenantId(UUID.fromString(tenant))
        val state = mapper.createObjectNode().apply { putArray("tailHashes").also { a -> tailHashes.forEach(a::add) } }
        val correlation = UUID.randomUUID().toString()
        val context =
            EventContext(
                tenantId,
                EntityRef.TENANT_WIDE,
                null,
                null,
                partitionId,
                null,
                CorrelationId(correlation),
                CausationId(correlation),
            )
        val entry =
            AuditEntry(
                actor = actorUserId,
                action = ACTION,
                scope = "partition:$partitionId",
                priorStateJson = mapper.writeValueAsString(state),
            )
        return writer.record(entry, context).id.value
    }

    companion object {
        const val ACTION = "deleteAtRetentionEnd"
    }
}
