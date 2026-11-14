package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.reconciliation.DocFact
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.gst.submission.domain.reconciliation.SentVersusAcknowledged
import com.dynokore.gst.submission.domain.reconciliation.VersionFact
import java.time.Instant

internal fun touchesMaskedFact(filter: SubmissionFilter): Boolean =
    filter.counterparty != null || filter.amountMin != null ||
        filter.amountMax != null || filter.categoryCode != null

/**
 * The reconciliation class of each document (INV-REQ-976 behavior 3): every row of a document shares its class,
 * so a document with versions ACKNOWLEDGED then REJECTED is SENT_ACKNOWLEDGED in all its rows. No deadline or
 * clock is carried, so the class never is SENT_NOT_ACKNOWLEDGED and the instant passed is never compared.
 */
internal fun documentBuckets(rows: List<SubmissionRow>): Map<SubmissionRow, ReconciliationBucket?> {
    val byDoc = rows.groupBy { it.docUuid }
    val classOf =
        byDoc.mapValues { (docUuid, group) ->
            val ordered = group.sortedBy { it.version }
            val versions = ordered.map { VersionFact(it.version, it.state, acknowledgementDeadline = null) }
            SentVersusAcknowledged.bucketOf(DocFact(docUuid, versions, ordered.last().taxableAmount), Instant.EPOCH)
        }
    return rows.associateWith { classOf[it.docUuid] }
}
