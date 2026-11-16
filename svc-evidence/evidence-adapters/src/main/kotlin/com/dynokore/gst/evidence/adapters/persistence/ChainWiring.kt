package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.events.AuditEntryRecordedHandler
import com.dynokore.gst.evidence.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.evidence.adapters.events.DecisionTraceRecordedHandler
import com.dynokore.gst.evidence.adapters.events.EvidenceArtefactRecordedHandler
import com.dynokore.gst.evidence.adapters.events.GstReturnRecordedHandler
import com.dynokore.gst.evidence.application.artefact.ArtefactRecorder
import com.dynokore.gst.evidence.application.chain.AcknowledgementRecorder
import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.application.digest.DigestObjects
import com.dynokore.gst.evidence.application.digest.MaxRetention
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.gst.evidence.application.integrity.ChainVerification
import com.dynokore.gst.evidence.application.integrity.ChainVerificationRecorder
import com.dynokore.gst.evidence.application.integrity.DigestCopies
import com.dynokore.gst.evidence.application.integrity.VerificationSources
import com.dynokore.gst.evidence.application.lookup.ChainRecordReader
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceIndex
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock

/** The appender over one [JdbcEvidenceLedger] per chain table and the three stores, on a tenant-bound [DSLContext]. */
@Suppress("TooManyFunctions") // one factory per handler of the chain consumers
object ChainWiring {
    const val SCHEMA = "evidence"

    /** The exclusive end of a one-period unit: the period followed by this character (V21). */
    private const val UNIT_END = "\u0001"

    /** Only `decision_trace` has the nullable `version` column (V17); the other chain tables have none. */
    private fun versionColumn(kind: RecordKind): Boolean = kind == RecordKind.DECISION_TRACE

    /** One ledger per chain table, the one place that decides which ledger writes and reads the `version` column. */
    internal fun ledgers(dsl: DSLContext): Map<RecordKind, JdbcEvidenceLedger> =
        RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, SCHEMA, it.table, versionColumn(it)) }

    /**
     * The ledgers of the appender: each append first calls `evidence.create_retention_partition` (V21) for its chain
     * table and the period's unit, in the caller's tenant transaction, so the row lands in a partition of its own
     * tenant (the table name is an inline literal that PostgreSQL reads as `regclass`). The unit is one period: the
     * range is [period, period + U+0001) on the "C" collation, so `P1` and `P10` are different units and no two
     * units overlap.
     */
    internal fun appendLedgers(dsl: DSLContext): Map<RecordKind, EvidenceLedger> =
        ledgers(dsl).mapValues { (kind, ledger) -> PartitionCreatingLedger(dsl, kind.table, ledger) }

    private class PartitionCreatingLedger(
        private val dsl: DSLContext,
        private val table: String,
        private val delegate: EvidenceLedger,
    ) : EvidenceLedger by delegate {
        @Suppress("LongParameterList") // the port signature of EvidenceLedger, unchanged
        override fun append(
            tenant: TenantId,
            entity: EntityRef,
            period: PeriodRef,
            type: EvidenceType,
            content: ByteArray,
            index: EvidenceIndex,
        ): EvidenceRecord {
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "create_retention_partition"),
                        SQLDataType.VARCHAR,
                        DSL.inline("$SCHEMA.$table"),
                        DSL.`val`(tenant.value),
                        DSL.`val`(entity.value),
                        DSL.`val`(period.value),
                        DSL.`val`(period.value + UNIT_END),
                    ),
                ).fetch()
            return delegate.append(tenant, entity, period, type, content, index)
        }
    }

    /** The chain record reader over the same ledgers as the appender, for the bootstrap (INV-REQ-691). */
    fun chainRecordReader(dsl: DSLContext): ChainRecordReader = JooqChainRecordReader(dsl, ledgers(dsl))

    fun recordAppender(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): RecordAppender =
        RecordAppender(
            appendLedgers(dsl),
            JooqLocatorStore(dsl),
            JooqPendingStore(dsl),
            JooqAppliedStore(dsl),
            clock,
        )

    fun locatorRecorder(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): LocatorRecorder =
        LocatorRecorder(
            JooqLocatorStore(dsl),
            recordAppender(dsl, clock),
            JooqSubmissionIndexStore(dsl),
            JooqSubmissionIndexCategoryStore(dsl),
            clock,
        )

    fun acknowledgementRecorder(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): AcknowledgementRecorder =
        AcknowledgementRecorder(
            JooqAcknowledgementLocatorStore(dsl),
            recordAppender(dsl, clock),
            JooqSubmissionIndexStore(dsl),
            clock,
        )

    /** The handler of `CanonicalVersionCreated`, to run inside the inbox consumer's transaction. */
    fun locatorHandler(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): EventHandler = CanonicalVersionCreatedHandler(locatorRecorder(dsl, clock))

    /** The handler of `DecisionTraceRecorded`, to run inside the inbox consumer's transaction. */
    fun decisionTraceHandler(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): EventHandler = DecisionTraceRecordedHandler(recordAppender(dsl, clock), clock)

    /** The handler of `AuditEntryRecorded`, to run inside the inbox consumer's transaction. */
    fun auditEntryHandler(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): EventHandler = AuditEntryRecordedHandler(recordAppender(dsl, clock), clock)

    /** The handler of `EvidenceArtefactRecorded`, to run inside the inbox consumer's transaction. */
    fun evidenceArtefactHandler(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): EventHandler = EvidenceArtefactRecordedHandler(ArtefactRecorder(JooqArtefactStore(dsl)), clock)

    /** The handler of `GstReturnRecorded`, to run inside the inbox consumer's transaction. */
    fun gstReturnRecordedHandler(
        dsl: DSLContext,
        objects: DigestObjects,
        retention: MaxRetention,
        clock: Clock = Clock.systemUTC(),
    ): EventHandler =
        GstReturnRecordedHandler(
            PeriodDigestRecorder(JooqChainTails(dsl), JooqPeriodDigestStore(dsl), objects, retention, clock),
        )

    /** The routine verification of the chains (INV-Q-7251 A (4)); it reads only and writes nothing. */
    fun chainVerification(
        dsl: DSLContext,
        copies: DigestCopies,
    ): ChainVerification {
        val ledgers = ledgers(dsl)
        return ChainVerification(
            { tenant, entityRef, period, kind ->
                ledgers.getValue(kind).verifyChain(tenant, EntityRef(entityRef), PeriodRef(period))
            },
            VerificationSources(JooqChainReads(dsl), JooqDigestReads(dsl), copies),
        )
    }

    /** The recording of one verification, to run in a tenant transaction per period. */
    fun chainVerificationRecorder(
        dsl: DSLContext,
        verification: ChainVerification,
        outbox: OutboxWriter,
        clock: Clock = Clock.systemUTC(),
    ): ChainVerificationRecorder =
        ChainVerificationRecorder(verification, JooqChainReads(dsl), JooqIntegrityStore(dsl), outbox, clock)
}
