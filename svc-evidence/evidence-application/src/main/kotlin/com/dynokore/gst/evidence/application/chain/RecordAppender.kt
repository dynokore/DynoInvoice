package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.ChainKey
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceIndex
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Appends a record to the chain of its kind exactly once, whichever event arrives first (INV-Q-7213 A (2) to (4)).
 * The chain tables have no event id column, so `applied_record` is the only marker that an event is applied: every
 * append here writes its row in the same transaction. Every method runs in the caller's tenant transaction.
 *
 * [ledgers] holds one ledger per [RecordKind], each over its own table.
 */
class RecordAppender(
    private val ledgers: Map<RecordKind, EvidenceLedger>,
    private val locators: LocatorStore,
    private val pending: PendingStore,
    private val applied: AppliedStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        check(ledgers.keys == RecordKind.entries.toSet()) { "a ledger is needed for every record kind" }
    }

    /**
     * Appends [content] to the chain of the locator of (tenant, [docUuid], [version]) in the table of [kind], or, when
     * the locator is not stored yet, keeps it in `pending_record` and appends nothing. Returns without any write when
     * [eventId] is applied.
     */
    @Suppress("LongParameterList") // the signature of INV-Q-7213 A (3)
    fun appendBound(
        tenant: TenantId,
        kind: RecordKind,
        docUuid: UUID,
        version: Int,
        eventId: String,
        content: ByteArray,
        arrivedAt: Instant,
    ) {
        require(kind != RecordKind.AUDIT_LOG) { "an audit entry is not bound to a document" }
        if (applied.isApplied(tenant, eventId)) return
        locators.lock(tenant, docUuid, version)
        val locator = locators.find(tenant, docUuid, version)
        if (locator == null) {
            val json = content.toString(Charsets.UTF_8)
            pending.insert(tenant, PendingRecord(eventId, kind, docUuid, version, json, arrivedAt))
        } else {
            append(
                tenant,
                Target(kind, locator.chain, EvidenceIndex(docUuid, locator.documentNumber)),
                eventId,
                content,
            )
        }
    }

    /** Appends [content] of an AuditEntryRecorded to the `audit_log` chain [chainKey], with an empty index. */
    @Suppress("UNUSED_PARAMETER") // arrivedAt is part of the slice's signature; only a pending record keeps it
    fun appendAudit(
        tenant: TenantId,
        chainKey: ChainKey,
        eventId: String,
        content: ByteArray,
        arrivedAt: Instant,
    ) {
        if (applied.isApplied(tenant, eventId)) return
        append(tenant, Target(RecordKind.AUDIT_LOG, chainKey, EvidenceIndex.EMPTY), eventId, content)
    }

    /**
     * Appends, in the order of arrival, every pending record of the document version that is not applied yet. Nothing
     * is updated or deleted: `applied_record` marks the records done. Returns the number appended. The locator must
     * be stored.
     */
    fun drainPending(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): Int {
        val locator =
            checkNotNull(locators.find(tenant, docUuid, version)) { "no locator for $docUuid version $version" }
        val records = pending.unapplied(tenant, docUuid, version)
        records.forEach {
            val target = Target(it.kind, locator.chain, EvidenceIndex(docUuid, locator.documentNumber))
            append(tenant, target, it.eventId, it.eventJson.toByteArray(Charsets.UTF_8))
        }
        return records.size
    }

    /** Where a record goes: the table of [kind], the chain [chain] and the index keys. */
    private class Target(
        val kind: RecordKind,
        val chain: ChainKey,
        val index: EvidenceIndex,
    )

    private fun append(
        tenant: TenantId,
        target: Target,
        eventId: String,
        content: ByteArray,
    ) {
        val record: EvidenceRecord =
            checkNotNull(ledgers[target.kind]).append(
                tenant,
                EntityRef(target.chain.entityRef),
                PeriodRef(target.chain.periodRef),
                target.kind.evidenceType(),
                content,
                target.index,
            )
        applied.insert(tenant, AppliedRecord(eventId, target.kind, target.chain, record.sequence, clock.instant()))
    }

    private fun RecordKind.evidenceType(): EvidenceType =
        when (this) {
            RecordKind.DECISION_TRACE -> EvidenceType.DECISION_TRACE
            RecordKind.EVIDENCE_PAYLOAD -> EvidenceType.PAYLOAD
            RecordKind.ACKNOWLEDGEMENT -> EvidenceType.ACKNOWLEDGEMENT
            RecordKind.AUDIT_LOG -> EvidenceType.AUDIT_LOG
        }
}
