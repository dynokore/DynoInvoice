package com.dynokore.gst.evidence.application.lookup

import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.domain.auditedread.AuditedCall
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** The permission key of `reverseLookup`; a decision names a permission, never a role (INV-Q-10027). */
const val ENQUIRY_LOOKUP_REVERSE = "enquiry-lookup.reverse"

sealed interface ReverseLookupResult {
    /** [nextCursor] is opaque (base64url) and null on the last page. */
    data class Page(
        val items: List<ReverseLookupRow>,
        val nextCursor: String?,
    ) : ReverseLookupResult

    /** The permission is not held, or the decision is unavailable (fail closed). */
    data object Forbidden : ReverseLookupResult

    /** A cursor that does not decode, or a limit outside 1 to [ReverseLookup.MAX_LIMIT]; maps to 400 SRT-001. */
    data object InvalidRequest : ReverseLookupResult
}

/**
 * Reverse lookup of document versions with their acknowledgement state (INV-REQ-724, INV-AC-20722). Order: decide the
 * permission, validate the cursor and limit (no filter is ever refused), then read inside the audited read whose entry
 * carries the acting user id. Only rows with a document date and a state are listed (round k ruling 11).
 */
class ReverseLookup(
    private val replica: AuthorityReplica,
    private val reader: SubmissionIndexReader,
    private val audited: AuditedReads,
) {
    @Suppress("LongParameterList") // the request of the operation: caller, filters, page, audit context
    fun lookup(
        tenant: TenantId,
        principal: Principal,
        filter: ReverseLookupFilter,
        limit: Int,
        cursor: String?,
        context: EventContext,
    ): ReverseLookupResult {
        val after = cursor?.let { decode(it) }
        return when {
            !replica.decideTenant(tenant, principal, ENQUIRY_LOOKUP_REVERSE).allowed -> ReverseLookupResult.Forbidden
            limit !in 1..MAX_LIMIT || (cursor != null && after == null) -> ReverseLookupResult.InvalidRequest
            else -> read(tenant, principal, filter, limit, after, context)
        }
    }

    @Suppress("LongParameterList") // one audited read of one page
    private fun read(
        tenant: TenantId,
        principal: Principal,
        filter: ReverseLookupFilter,
        limit: Int,
        after: ReverseLookupKey?,
        context: EventContext,
    ): ReverseLookupResult =
        audited.perform(AuditedCall.ReverseLookup(parametersOf(filter)), principal.id, context) {
            val rows = reader.read(tenant, filter, after, limit + 1)
            val page = rows.take(limit)
            ReverseLookupResult.Page(page, if (rows.size > limit) encode(page.last().key) else null)
        }

    private fun parametersOf(filter: ReverseLookupFilter): Map<ReverseLookupParameter, String> =
        buildMap {
            filter.docUuid?.let { put(ReverseLookupParameter.DOC_UUID, it.toString()) }
            filter.documentNumber?.let { put(ReverseLookupParameter.DOCUMENT_NUMBER, it) }
            filter.counterparty?.let { put(ReverseLookupParameter.COUNTERPARTY, it) }
            filter.dateFrom?.let { put(ReverseLookupParameter.DATE_FROM, it.toString()) }
            filter.dateTo?.let { put(ReverseLookupParameter.DATE_TO, it.toString()) }
            filter.periodRef?.let { put(ReverseLookupParameter.PERIOD_REF, it) }
            filter.categoryCode?.let { put(ReverseLookupParameter.CATEGORY_CODE, it) }
        }

    companion object {
        /** The contract's `limit` maximum (`reverseLookup`). */
        const val MAX_LIMIT = 500

        private const val PARTS = 3
        private const val SEPARATOR = '|'

        fun encode(key: ReverseLookupKey): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("${key.documentDate}$SEPARATOR${key.docUuid}$SEPARATOR${key.version}".toByteArray())

        /** Null when [cursor] is not a canonical cursor of this service; only a cursor [encode] can produce decodes. */
        fun decode(cursor: String): ReverseLookupKey? =
            runCatching {
                val parts = String(Base64.getUrlDecoder().decode(cursor)).split(SEPARATOR)
                require(parts.size == PARTS)
                ReverseLookupKey(LocalDate.parse(parts[0]), UUID.fromString(parts[1]), parts[PARTS - 1].toInt())
            }.getOrNull()?.takeIf { encode(it) == cursor }
    }
}
