package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Acknowledgement.Companion.ACKNOWLEDGEMENT
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AuditLog.Companion.AUDIT_LOG
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DecisionTrace.Companion.DECISION_TRACE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidencePayload.Companion.EVIDENCE_PAYLOAD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityFailure.Companion.INTEGRITY_FAILURE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityRun.Companion.INTEGRITY_RUN
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigest.Companion.PERIOD_DIGEST
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PeriodDigestChain.Companion.PERIOD_DIGEST_CHAIN
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.records.PeriodDigestChainRecord
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.records.PeriodDigestRecord
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.application.integrity.ChainFacts
import com.dynokore.gst.evidence.application.integrity.ChainReads
import com.dynokore.gst.evidence.application.integrity.DigestReads
import com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.gst.evidence.domain.integrity.evidenceType
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.OffsetDateTime
import java.util.UUID

/** The chain tables and `applied_record` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqChainReads(
    private val dsl: DSLContext,
) : ChainReads {
    /** The columns of one chain table that the verification reads. */
    private class Columns(
        val tenant: Field<UUID?>,
        val entity: Field<String?>,
        val period: Field<String?>,
        val seq: TableField<*, Long?>,
        val hash: Field<String?>,
        val recordedAt: Field<OffsetDateTime?>,
    ) {
        val table: Table<*> get() = checkNotNull(seq.table)
    }

    private val columns =
        mapOf(
            RecordKind.EVIDENCE_PAYLOAD to
                Columns(
                    EVIDENCE_PAYLOAD.TENANT_ID,
                    EVIDENCE_PAYLOAD.ENTITY_REF,
                    EVIDENCE_PAYLOAD.PERIOD_REF,
                    EVIDENCE_PAYLOAD.SEQ,
                    EVIDENCE_PAYLOAD.CONTENT_HASH,
                    EVIDENCE_PAYLOAD.RECORDED_AT,
                ),
            RecordKind.ACKNOWLEDGEMENT to
                Columns(
                    ACKNOWLEDGEMENT.TENANT_ID,
                    ACKNOWLEDGEMENT.ENTITY_REF,
                    ACKNOWLEDGEMENT.PERIOD_REF,
                    ACKNOWLEDGEMENT.SEQ,
                    ACKNOWLEDGEMENT.CONTENT_HASH,
                    ACKNOWLEDGEMENT.RECORDED_AT,
                ),
            RecordKind.DECISION_TRACE to
                Columns(
                    DECISION_TRACE.TENANT_ID,
                    DECISION_TRACE.ENTITY_REF,
                    DECISION_TRACE.PERIOD_REF,
                    DECISION_TRACE.SEQ,
                    DECISION_TRACE.CONTENT_HASH,
                    DECISION_TRACE.RECORDED_AT,
                ),
            RecordKind.AUDIT_LOG to
                Columns(
                    AUDIT_LOG.TENANT_ID,
                    AUDIT_LOG.ENTITY_REF,
                    AUDIT_LOG.PERIOD_REF,
                    AUDIT_LOG.SEQ,
                    AUDIT_LOG.CONTENT_HASH,
                    AUDIT_LOG.RECORDED_AT,
                ),
        )

    override fun periods(
        tenant: TenantId,
        entityRef: String,
    ): List<String> =
        dsl
            .select(APPLIED_RECORD.PERIOD_REF)
            .from(APPLIED_RECORD)
            .where(APPLIED_RECORD.TENANT_ID.eq(tenant.value), APPLIED_RECORD.ENTITY_REF.eq(entityRef))
            .union(
                DSL
                    .select(PERIOD_DIGEST.PERIOD_REF)
                    .from(PERIOD_DIGEST)
                    .where(PERIOD_DIGEST.TENANT_ID.eq(tenant.value), PERIOD_DIGEST.ENTITY_REF.eq(entityRef)),
            ).fetch(0, String::class.java)
            .filterNotNull()
            .sorted()

    override fun kinds(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<RecordKind> =
        dsl
            .selectDistinct(APPLIED_RECORD.KIND)
            .from(APPLIED_RECORD)
            .where(
                APPLIED_RECORD.TENANT_ID.eq(tenant.value),
                APPLIED_RECORD.ENTITY_REF.eq(entityRef),
                APPLIED_RECORD.PERIOD_REF.eq(period),
            ).fetch(0, String::class.java)
            .map { table -> RecordKind.entries.single { it.table == table } }
            .sortedBy { it.ordinal }

    override fun facts(
        tenant: TenantId,
        entityRef: String,
        period: String,
        kind: RecordKind,
    ): ChainFacts {
        val c = checkNotNull(columns[kind])
        val row =
            dsl
                .select(DSL.count(), DSL.min(c.recordedAt), DSL.max(c.recordedAt))
                .from(c.table)
                .where(c.tenant.eq(tenant.value), c.entity.eq(entityRef), c.period.eq(period))
                .fetchOne()
        return ChainFacts(
            checkNotNull(row).value1().toLong(),
            row.value2()?.toInstant(),
            row.value3()?.toInstant(),
        )
    }

    override fun hashAt(
        tenant: TenantId,
        entityRef: String,
        period: String,
        kind: RecordKind,
        sequence: Long,
    ): String? {
        val c = checkNotNull(columns[kind])
        return dsl
            .select(c.hash)
            .from(c.table)
            .where(c.tenant.eq(tenant.value), c.entity.eq(entityRef), c.period.eq(period), c.seq.eq(sequence))
            .fetchOne(0, String::class.java)
    }
}

/** `period_digest` and `period_digest_chain`, read for the verification. */
class JooqDigestReads(
    private val dsl: DSLContext,
) : DigestReads {
    override fun digests(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<PeriodDigestEntry> {
        val chainRows =
            dsl
                .selectFrom(PERIOD_DIGEST_CHAIN)
                .where(
                    PERIOD_DIGEST_CHAIN.TENANT_ID.eq(tenant.value),
                    PERIOD_DIGEST_CHAIN.ENTITY_REF.eq(entityRef),
                    PERIOD_DIGEST_CHAIN.PERIOD_REF.eq(period),
                ).fetch()
                .groupBy { it.digestSeq }
        return dsl
            .selectFrom(PERIOD_DIGEST)
            .where(
                PERIOD_DIGEST.TENANT_ID.eq(tenant.value),
                PERIOD_DIGEST.ENTITY_REF.eq(entityRef),
                PERIOD_DIGEST.PERIOD_REF.eq(period),
            ).orderBy(PERIOD_DIGEST.DIGEST_SEQ)
            .fetch()
            .map { row ->
                entryOf(row, entityRef, period, chainRows[row.digestSeq].orEmpty())
            }
    }
}

private fun entryOf(
    row: PeriodDigestRecord,
    entityRef: String,
    period: String,
    chainRows: List<PeriodDigestChainRecord>,
) = PeriodDigestEntry(
    entityRef = entityRef,
    period = period,
    digestSequence = checkNotNull(row.digestSeq),
    returnVersion = checkNotNull(row.returnVersion),
    filedAt = checkNotNull(row.filedAt).toInstant(),
    recordedAt = checkNotNull(row.recordedAt).toInstant(),
    eventId = checkNotNull(row.sourceEventId),
    chains =
        chainRows.sortedBy { DigestChainType.valueOf(checkNotNull(it.evidenceType)) }.map {
            val length = checkNotNull(it.chainLength)
            ChainTail(DigestChainType.valueOf(checkNotNull(it.evidenceType)), length, it.tailHash.takeIf { length > 0 })
        },
    digest = checkNotNull(row.digest),
)

/** `integrity_run` and `integrity_failure` over the tenant-bound [DSLContext]; nothing here rewrites a row. */
class JooqIntegrityStore(
    private val dsl: DSLContext,
) : IntegrityStore {
    override fun lock(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ) {
        val key = DSL.`val`("integrity:${tenant.value}:$entityRef:$period")
        val hash = DSL.function(DSL.name("hashtextextended"), SQLDataType.BIGINT, key, DSL.`val`(0L))
        dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, hash)).fetch()
    }

    override fun failureExists(
        tenant: TenantId,
        row: IntegrityFailureRow,
    ): Boolean =
        dsl.fetchExists(
            INTEGRITY_FAILURE,
            INTEGRITY_FAILURE.TENANT_ID.eq(tenant.value),
            INTEGRITY_FAILURE.ENTITY_REF.eq(row.entityRef),
            INTEGRITY_FAILURE.PERIOD_REF.eq(row.period),
            INTEGRITY_FAILURE.EVIDENCE_TYPE.eq(row.kind.evidenceType),
            INTEGRITY_FAILURE.KIND.eq(row.failure.name),
            DSL.coalesce(INTEGRITY_FAILURE.FAILING_SEQ, -1L).eq(row.failingSequence ?: -1L),
        )

    override fun insertFailure(
        tenant: TenantId,
        row: IntegrityFailureRow,
    ) {
        dsl
            .insertInto(INTEGRITY_FAILURE)
            .set(INTEGRITY_FAILURE.TENANT_ID, tenant.value)
            .set(INTEGRITY_FAILURE.ENTITY_REF, row.entityRef)
            .set(INTEGRITY_FAILURE.PERIOD_REF, row.period)
            .set(INTEGRITY_FAILURE.EVIDENCE_TYPE, row.kind.evidenceType)
            .set(INTEGRITY_FAILURE.KIND, row.failure.name)
            .set(INTEGRITY_FAILURE.FAILING_SEQ, row.failingSequence)
            .set(INTEGRITY_FAILURE.RANGE_FROM, row.rangeFrom.utc())
            .set(INTEGRITY_FAILURE.RANGE_TO, row.rangeTo.utc())
            .set(INTEGRITY_FAILURE.RUN_AT, row.runAt.utc())
            .set(INTEGRITY_FAILURE.RUN_ID, row.runId)
            .execute()
    }

    override fun insertRun(
        tenant: TenantId,
        row: IntegrityRunRow,
    ) {
        dsl
            .insertInto(INTEGRITY_RUN)
            .set(INTEGRITY_RUN.TENANT_ID, tenant.value)
            .set(INTEGRITY_RUN.ENTITY_REF, row.entityRef)
            .set(INTEGRITY_RUN.PERIOD_REF, row.period)
            .set(INTEGRITY_RUN.RUN_ID, row.runId)
            .set(INTEGRITY_RUN.RUN_AT, row.runAt.utc())
            .set(INTEGRITY_RUN.RESULT, row.result.name)
            .set(INTEGRITY_RUN.CHAIN_LENGTH, row.chainLength)
            .set(INTEGRITY_RUN.PERIOD_DIGEST, row.periodDigest)
            .execute()
    }

    override fun latestRun(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): IntegrityRunRow? =
        dsl
            .selectFrom(INTEGRITY_RUN)
            .where(
                INTEGRITY_RUN.TENANT_ID.eq(tenant.value),
                INTEGRITY_RUN.ENTITY_REF.eq(entityRef),
                INTEGRITY_RUN.PERIOD_REF.eq(period),
            ).orderBy(INTEGRITY_RUN.RUN_AT.desc(), INTEGRITY_RUN.SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let {
                IntegrityRunRow(
                    entityRef,
                    period,
                    checkNotNull(it.runId),
                    checkNotNull(it.runAt).toInstant(),
                    RunResult.valueOf(checkNotNull(it.result)),
                    checkNotNull(it.chainLength),
                    it.periodDigest,
                )
            }
}
