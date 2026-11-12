package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The row of `acknowledgement_locator`: an Acknowledgement ID and the document version it belongs to. */
data class AcknowledgementLocator(
    val ackId: String,
    val docUuid: UUID,
    val version: Int,
)

/** `acknowledgement_locator`, append-only. Every method runs in the caller's tenant transaction. */
interface AcknowledgementLocatorStore {
    /** Stores the row and returns true; false and nothing stored when (tenant, ack id) or [eventId] has a row. */
    @Suppress("LongParameterList") // the columns of one row
    fun insert(
        tenant: TenantId,
        eventId: String,
        ackId: String,
        docUuid: UUID,
        version: Int,
        recordedAt: Instant,
    ): Boolean

    fun find(
        tenant: TenantId,
        ackId: String,
    ): AcknowledgementLocator?
}

/**
 * Handles one SubmissionAcknowledged event (INV-REQ-691, ruling of 2026-10-14 case 1) in the caller's transaction:
 * writes the locator row, appends one ACKNOWLEDGEMENT record to the chain of (docUuid, version) and sets the
 * acknowledgement columns of the `submission_index` row (INV-REQ-724; the first acknowledgement of a version stays).
 *
 * A re-delivered event id, or the same acknowledgement for the same version in a new event, writes nothing. An
 * Acknowledgement ID already stored for another (docUuid, version) is a [ContractViolation], checked before any write.
 */
class AcknowledgementRecorder(
    private val locators: AcknowledgementLocatorStore,
    private val appender: RecordAppender,
    private val index: SubmissionIndexStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** True when the locator and the record were written. */
    @Suppress("LongParameterList") // one event's identity and content
    fun record(
        tenant: TenantId,
        eventId: String,
        ackId: String,
        docUuid: UUID,
        version: Int,
        content: ByteArray,
        acknowledgedAt: Instant,
    ): Boolean {
        val known = locators.find(tenant, ackId)
        if (known != null) {
            if (known.docUuid != docUuid || known.version != version) {
                throw ContractViolation("the acknowledgement id is already stored for another document version")
            }
            return false
        }
        val now = clock.instant()
        return locators.insert(tenant, eventId, ackId, docUuid, version, now).also { inserted ->
            if (inserted) {
                appender.appendBound(
                    tenant,
                    RecordKind.ACKNOWLEDGEMENT,
                    docUuid,
                    version,
                    eventId,
                    content,
                    now,
                )
                index.upsertAcknowledgement(tenant, docUuid, version, ackId, acknowledgedAt)
            }
        }
    }
}
