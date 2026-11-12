package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.events.AuditEntryRecordedHandler
import com.dynokore.gst.evidence.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.evidence.adapters.events.DecisionTraceRecordedHandler
import com.dynokore.gst.evidence.adapters.events.EvidenceArtefactRecordedHandler
import com.dynokore.gst.evidence.adapters.events.GstReturnRecordedHandler
import com.dynokore.gst.evidence.application.artefact.ArtefactRecorder
import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.application.digest.DigestObjects
import com.dynokore.gst.evidence.application.digest.MaxRetention
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.gst.evidence.application.integrity.ChainVerification
import com.dynokore.gst.evidence.application.integrity.ChainVerificationRecorder
import com.dynokore.gst.evidence.application.integrity.DigestCopies
import com.dynokore.gst.evidence.application.integrity.VerificationSources
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.jooq.DSLContext
import java.time.Clock

/** The appender over one [JdbcEvidenceLedger] per chain table and the three stores, on a tenant-bound [DSLContext]. */
object ChainWiring {
    const val SCHEMA = "evidence"

    /** Only `decision_trace` has the nullable `version` column (V17); the other chain tables have none. */
    private fun versionColumn(kind: RecordKind): Boolean = kind == RecordKind.DECISION_TRACE

    fun recordAppender(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): RecordAppender =
        RecordAppender(
            RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, SCHEMA, it.table, versionColumn(it)) },
            JooqLocatorStore(dsl),
            JooqPendingStore(dsl),
            JooqAppliedStore(dsl),
            clock,
        )

    fun locatorRecorder(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): LocatorRecorder = LocatorRecorder(JooqLocatorStore(dsl), recordAppender(dsl, clock), clock)

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
        val ledgers = RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, SCHEMA, it.table, versionColumn(it)) }
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
