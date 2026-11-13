package com.dynokore.gst.evidence.application.lookup

import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQuery
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.domain.auditedread.AuditedCall
import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** The permission key of `getAcknowledgementChain`; a decision names a permission, never a role (INV-Q-10027). */
const val ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT = "enquiry-lookup.by-acknowledgement"

/** The kinds of chain item this slice answers; the decision-trace item waits for its own ruling (INV-REQ-942). */
enum class ChainItemKind { PAYLOAD, ACKNOWLEDGEMENT }

/**
 * One item of the chain. [sequence] is the 1-based rank in the append order of the whole version (never the raw
 * per-table `seq`); [key] is the order key the rank comes from and the page cursor carries.
 */
data class ChainItem(
    val kind: ChainItemKind,
    val recordedAt: Instant,
    val reference: String,
    val sequence: Long,
    val key: ChainCursor,
)

/** The answer of [ChainRecordReader]: the items of the version, or a stored link whose record is missing. */
sealed interface ChainRecords {
    /**
     * Every item of the version, ordered by [ChainItem.key], with [ChainItem.sequence] the rank in that order;
     * [entityRef] and [periodRef] are the one pair of the version's records.
     */
    data class Complete(
        val items: List<ChainItem>,
        val entityRef: String,
        val periodRef: String,
    ) : ChainRecords

    /** A locator or sealed-payload link has no `applied_record` row, or the chain lacks the record it names. */
    data object Incomplete : ChainRecords
}

/** Reads the PAYLOAD and ACKNOWLEDGEMENT items of one version in the caller's tenant transaction (INV-REQ-726). */
fun interface ChainRecordReader {
    fun read(
        tenant: TenantId,
        ackId: String,
        docUuid: UUID,
        version: Int,
    ): ChainRecords
}

/**
 * The order key of an item: append instant, then kind (PAYLOAD before ACKNOWLEDGEMENT), then raw `seq`. A page starts
 * strictly after the key in the cursor; the cursor never holds a rank.
 */
data class ChainCursor(
    val appliedAt: Instant,
    val kind: ChainItemKind,
    val seq: Long,
) : Comparable<ChainCursor> {
    override fun compareTo(other: ChainCursor): Int =
        compareValuesBy(this, other, ChainCursor::appliedAt, ChainCursor::kind, ChainCursor::seq)
}

sealed interface ChainLookupResult {
    data class Found(
        val ackId: String,
        val docUuid: UUID,
        val version: Int,
        val items: List<ChainItem>,
        val nextCursor: ChainCursor?,
        val integrityStatus: IntegrityState,
    ) : ChainLookupResult

    /** An unknown id and the id of another tenant answer the same. */
    data object NotFound : ChainLookupResult

    /** The permission is not held, or the decision is unavailable (fail closed). */
    data object Forbidden : ChainLookupResult

    /** A stored link has no record behind it; never an empty success. */
    data object Incomplete : ChainLookupResult
}

/**
 * Lookup by Acknowledgement ID (INV-REQ-691, INV-AC-20574). Order: decide the permission, then find the locator (one
 * indexed read), then read the records, the last two inside the audited read whose entry carries the acting user id.
 */
class AcknowledgementChainLookup(
    private val replica: AuthorityReplica,
    private val locators: AcknowledgementLocatorStore,
    private val records: ChainRecordReader,
    private val integrity: IntegrityStatusQueryHandler,
    private val audited: AuditedReads,
) {
    @Suppress("LongParameterList") // the request of the operation: caller, id, page, audit context
    fun lookup(
        tenant: TenantId,
        principal: Principal,
        ackId: String,
        limit: Int,
        cursor: ChainCursor?,
        context: EventContext,
    ): ChainLookupResult {
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        val decision = replica.decideTenant(tenant, principal, ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT)
        if (!decision.allowed) return ChainLookupResult.Forbidden
        return audited.perform(AuditedCall.AcknowledgementChain(ackId), principal.id, context) {
            val locator = locators.find(tenant, ackId)
            if (locator == null) {
                ChainLookupResult.NotFound
            } else {
                when (val read = records.read(tenant, locator.ackId, locator.docUuid, locator.version)) {
                    ChainRecords.Incomplete -> {
                        ChainLookupResult.Incomplete
                    }

                    is ChainRecords.Complete -> {
                        val state =
                            integrity.handle(tenant, IntegrityStatusQuery(read.entityRef, read.periodRef)).result
                        page(locator.ackId, locator.docUuid, locator.version, read.items, limit, cursor, state)
                    }
                }
            }
        }
    }

    @Suppress("LongParameterList") // one page of one version
    private fun page(
        ackId: String,
        docUuid: UUID,
        version: Int,
        items: List<ChainItem>,
        limit: Int,
        cursor: ChainCursor?,
        integrityStatus: IntegrityState,
    ): ChainLookupResult.Found {
        val ordered = items.sortedBy { it.key }
        val rest = if (cursor == null) ordered else ordered.filter { it.key > cursor }
        val page = rest.take(limit)
        val next = if (rest.size > limit) page.last().key else null
        return ChainLookupResult.Found(ackId, docUuid, version, page, next, integrityStatus)
    }

    companion object {
        /** The contract's `limit` maximum (`getAcknowledgementChain`). */
        const val MAX_LIMIT = 500
    }
}
