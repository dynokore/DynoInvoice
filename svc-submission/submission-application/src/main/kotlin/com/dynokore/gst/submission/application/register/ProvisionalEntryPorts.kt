package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.register.DateElection
import com.dynokore.gst.submission.domain.register.DocumentDirection
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/** A provisional register entry of a document held before its scope verdict (INV-REQ-570 behavior 2). */
data class ProvisionalEntry(
    val docUuid: String,
    val entityRef: String,
    val periodRef: String,
    val direction: DocumentDirection,
    val documentDate: LocalDate,
    val transactionDate: LocalDate,
    val reasonCode: String?,
    val docsequence: Long,
    val heldAt: Instant,
)

/** The members of a `DocumentHeld` event that the handler reads. */
data class DocumentHeldData(
    val docUuid: String,
    val docsequence: Long,
    val holdReasons: List<String>,
    val entityRef: String,
    val direction: String,
    val documentDate: LocalDate,
    val postingDate: LocalDate?,
    val invoiceIssuedOrReceived: Boolean?,
)

/** The members of a `DocumentReleased` event that the handler reads. */
data class DocumentReleasedData(
    val docUuid: String,
    val docsequence: Long,
)

/** Reads the event JSON. Throws `ContractViolation` for a missing or malformed member. */
fun interface DocumentHeldDataReader {
    fun read(dataJson: String): DocumentHeldData
}

/** Reads the event JSON. Throws `ContractViolation` for a missing or malformed member. */
fun interface DocumentReleasedDataReader {
    fun read(dataJson: String): DocumentReleasedData
}

enum class Placement { PLACED, UNCHANGED, ALREADY_REGISTERED }

/** Stored provisional entries; both calls run in the consumer's transaction. */
interface ProvisionalEntryStore {
    /**
     * Places the entry. A stored docsequence at or above the entry's gives [Placement.UNCHANGED]; a register entry
     * of the same docUuid gives [Placement.ALREADY_REGISTERED] and nothing is written.
     */
    fun place(
        tenant: TenantId,
        entry: ProvisionalEntry,
    ): Placement

    /** Clears the hold reason; true only when the stored docsequence is below [docsequence] and the row changed. */
    fun release(
        tenant: TenantId,
        docUuid: String,
        docsequence: Long,
    ): Boolean
}

/** The date election of an entity. The production source is not decided, so it answers `Unknown` until it is. */
fun interface DateElectionSource {
    fun electionOf(
        tenant: TenantId,
        entityRef: String,
    ): DateElection
}
