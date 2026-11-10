package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.platform.tenancykernel.TenantId

/**
 * What one read of the list source returns: the rows, and the number of version rows left out because their register
 * entry has no facts yet or the version row cannot form a [SubmissionRow]. The count lets the caller log it; no row is
 * dropped silently.
 */
data class SourceRead(
    val rows: List<SubmissionRow>,
    val withoutFacts: Int,
)

/**
 * The one source of list rows (INV-REQ-975): every version of every document of the entity and period, superseded
 * versions included, unfiltered and unordered. The filter, the summary, the cursor and the export read this port.
 */
interface SubmissionRowSource {
    fun rows(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): SourceRead
}
