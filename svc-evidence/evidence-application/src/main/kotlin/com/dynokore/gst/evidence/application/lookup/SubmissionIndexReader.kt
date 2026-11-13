package com.dynokore.gst.evidence.application.lookup

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The filters of `reverseLookup`; each present one is an equality match except the inclusive date range. */
data class ReverseLookupFilter(
    val counterparty: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val documentNumber: String? = null,
    val docUuid: UUID? = null,
    val periodRef: String? = null,
    val categoryCode: String? = null,
)

/** The keyset position of a row: the order of the reverse lookup is (document date, document, version). */
data class ReverseLookupKey(
    val documentDate: LocalDate,
    val docUuid: UUID,
    val version: Int,
)

/**
 * One matching document version. [state] is the stored lifecycle state verbatim (never null: a row without state is
 * not returned); [ackId] and [acknowledgedAt] are set only once the acknowledgement is written.
 * [entityRef] and [periodRef] are as written, null when absent.
 */
data class ReverseLookupRow(
    val key: ReverseLookupKey,
    val state: String,
    val ackId: String?,
    val acknowledgedAt: Instant?,
    val entityRef: String?,
    val periodRef: String?,
)

/** Reads `submission_index` in the caller's tenant transaction (INV-REQ-724). */
fun interface SubmissionIndexReader {
    /**
     * Complete rows only (document date and state present), filters combined with AND, ordered by [ReverseLookupRow.key]
     * ascending, strictly after [after] when given, at most [limit] rows.
     */
    fun read(
        tenant: TenantId,
        filter: ReverseLookupFilter,
        after: ReverseLookupKey?,
        limit: Int,
    ): List<ReverseLookupRow>
}
