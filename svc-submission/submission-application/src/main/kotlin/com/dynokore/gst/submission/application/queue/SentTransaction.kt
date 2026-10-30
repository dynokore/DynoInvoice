package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.util.UUID

/** What the despatch step hands to the transport step: the committed attempt and the item, still `CLAIMED`. */
data class Dispatch(
    val docUuid: String,
    val version: Int,
    val attemptNumber: Int,
    val correlationId: UUID,
    val requestBytes: ByteArray?,
    val item: ClaimedItem,
)

/** The outcome of [SentTransaction.perform]: a [Dispatch] after the commit, or the row's state with nothing written. */
sealed interface SentResult {
    data class Sent(
        val dispatch: Dispatch,
    ) : SentResult

    /** The row is in a state outside the eligible set; no write was made and [item] stays `CLAIMED`. */
    data class NotEligible(
        val state: LifecycleState,
        val item: ClaimedItem,
    ) : SentResult
}

/**
 * Step (3) of the claim flow (INV-Q-7266 A, INV-REQ-540 behaviour 2, INV-REQ-1156 behaviour 2): in one tenant
 * transaction the row moves to `SENT`, the attempt count increases, the attempt row is written with the request and a
 * new correlation ID, and one `SubmissionDespatched` event goes to the outbox. The item stays `CLAIMED`; the
 * transport call happens after this commit, in the attempts slice (INV-REQ-1156 behaviour 4).
 *
 * The event context comes from the aggregate row (transactional outbox, INV-Q-13469 A): the entity and transaction date
 * stored by the consumer slice, the Doc UUID as the aggregate key.
 */
class SentTransaction(
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val store: SubmissionStore,
    private val queue: WorkQueue,
    private val outbox: OutboxWriter,
    private val principal: WorkloadPrincipal,
) {
    private val clock: Clock = Clock.systemUTC()

    /** @param eligible the states from which the row may move to `SENT`. */
    fun perform(
        item: ClaimedItem,
        eligible: Set<LifecycleState>,
    ): SentResult {
        // claimNext binds no tenant (the cross-tenant claim); the item's own tenant is bound for step (3).
        return tenants.bind(item.tenant, principal.value) { performBound(item, eligible) }
    }

    private fun performBound(
        item: ClaimedItem,
        eligible: Set<LifecycleState>,
    ): SentResult =
        transactions.inTransaction {
            val tenant = tenants.current()
            val (docUuid, version) = ItemPayload.parse(queue.payloadOf(item))
            val row =
                checkNotNull(store.lockForUpdate(tenant, docUuid, version)) {
                    "item ${item.seq} names a submission that does not exist: $docUuid version $version"
                }
            if (row.state !in eligible) return@inTransaction SentResult.NotEligible(row.state, item)
            val attemptNumber = row.attemptCount + 1
            val correlation = UUID.randomUUID()
            store.markSent(tenant, docUuid, version, attemptNumber)
            store.insertAttempt(
                tenant,
                NewAttempt(docUuid, version, attemptNumber, row.payloadBytes, correlation, clock.instant()),
            )
            outbox.append(
                listOf(OutboundEvent(DESPATCHED, despatchedData(docUuid, version, attemptNumber, row))),
                EventContext(
                    tenant = tenant,
                    entity = EntityRef(row.entityRef),
                    docUuid = DocUuid(UUID.fromString(docUuid)),
                    transactionDate = row.transactionDate,
                    aggregateKey = docUuid,
                    aggregateVersion = null,
                    correlation = CorrelationId(correlation.toString()),
                    causation = CausationId("queue-item:${item.seq}"),
                ),
            )
            SentResult.Sent(Dispatch(docUuid, version, attemptNumber, correlation, row.payloadBytes, item))
        }

    private fun despatchedData(
        docUuid: String,
        version: Int,
        attemptNumber: Int,
        row: LockedSubmission,
    ): String {
        val sha = checkNotNull(row.payloadSha256) { "submission $docUuid version $version holds no payload SHA-256" }
        return """{"docUuid":"$docUuid","version":$version,"attemptNumber":$attemptNumber,"payloadSha256":"$sha"}"""
    }

    companion object {
        const val DESPATCHED = "com.dynokore.gst.submission.SubmissionDespatched.v1"
    }
}

/** The queue item payload: a JSON object with exactly the members `docUuid` and `version` (see [SubmissionQueue]). */
internal object ItemPayload {
    private val SHAPE =
        Regex("""\{\s*"docUuid"\s*:\s*"([0-9a-f-]{36})"\s*,\s*"version"\s*:\s*([0-9]{1,9})\s*}""")
    private val SHAPE_SWAPPED =
        Regex("""\{\s*"version"\s*:\s*([0-9]{1,9})\s*,\s*"docUuid"\s*:\s*"([0-9a-f-]{36})"\s*}""")

    fun parse(json: String): Pair<String, Int> {
        val text = json.trim()
        SHAPE.matchEntire(text)?.let { return doc(it.groupValues[1]) to it.groupValues[2].toInt() }
        SHAPE_SWAPPED.matchEntire(text)?.let { return doc(it.groupValues[2]) to it.groupValues[1].toInt() }
        throw IllegalArgumentException("a submission queue item holds exactly docUuid and version")
    }

    private fun doc(text: String): String = UUID.fromString(text).toString()
}
