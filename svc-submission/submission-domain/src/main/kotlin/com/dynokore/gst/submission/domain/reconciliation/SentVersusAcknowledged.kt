package com.dynokore.gst.submission.domain.reconciliation

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** The five sent-versus-acknowledged classes (INV-REQ-976); the declaration order is the contract order. */
enum class ReconciliationBucket {
    SENT_ACKNOWLEDGED,
    SENT_NOT_ACKNOWLEDGED,
    REJECTED,
    DEAD_LETTERED,
    NEVER_SENT,
    ;

    companion object {
        /** The member named exactly [key]; any other string, including a different case, is refused. */
        fun fromKey(key: String): ReconciliationBucket =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown reconciliation bucket key: '$key'")
    }
}

/** One version of a document; [acknowledgementDeadline] is computed by the caller. */
data class VersionFact(
    val version: Int,
    val state: LifecycleState,
    val acknowledgementDeadline: Instant?,
) {
    init {
        require(version >= 1) { "version must be at least 1" }
    }
}

/** A document of the period with its versions in increasing version order and its amount. */
data class DocFact(
    val docUuid: String,
    val versions: List<VersionFact>,
    val amount: BigDecimal,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(versions.isNotEmpty()) { "versions must not be empty" }
        require(versions.zipWithNext().all { (a, b) -> a.version < b.version }) {
            "versions must be in strictly increasing version order"
        }
        require(amount.scale() <= 2) { "amount must have at most 2 decimals" }
    }
}

/** The count and the exact total amount of one class. */
data class BucketTotal(
    val bucket: ReconciliationBucket,
    val count: Int,
    val totalAmount: BigDecimal,
)

/** The pure rule of the sent-versus-acknowledged view (INV-REQ-976, ruling INV-Q-5944 A, INV-Q-16278 A). */
object SentVersusAcknowledged {
    /** The class of [doc] at [now], or null when the document is in no class. */
    fun bucketOf(
        doc: DocFact,
        now: Instant,
    ): ReconciliationBucket? {
        if (doc.versions.any { it.state == LifecycleState.ACKNOWLEDGED }) {
            return ReconciliationBucket.SENT_ACKNOWLEDGED
        }
        val last = doc.versions.last()
        return when (last.state) {
            LifecycleState.SENT, LifecycleState.SENT_UNCONFIRMED -> {
                val deadline = last.acknowledgementDeadline
                if (deadline != null && now.isAfter(deadline)) ReconciliationBucket.SENT_NOT_ACKNOWLEDGED else null
            }

            LifecycleState.ACKNOWLEDGED -> {
                ReconciliationBucket.SENT_ACKNOWLEDGED
            }

            LifecycleState.REJECTED -> {
                ReconciliationBucket.REJECTED
            }

            LifecycleState.DEAD_LETTERED -> {
                ReconciliationBucket.DEAD_LETTERED
            }

            LifecycleState.INGESTED,
            LifecycleState.HELD,
            LifecycleState.BLOCKED,
            LifecycleState.READY,
            LifecycleState.AWAITING_PRECEDING,
            LifecycleState.QUEUED,
            LifecycleState.RETRY,
            -> {
                ReconciliationBucket.NEVER_SENT
            }

            LifecycleState.NOT_SUBMITTABLE,
            LifecycleState.CANCELLED,
            LifecycleState.WITHDRAWN,
            LifecycleState.SUPERSEDED,
            LifecycleState.EXTERNALLY_SUBMITTED,
            LifecycleState.NETWORK_ISSUED,
            -> {
                null
            }
        }
    }

    /** Exactly five totals in the order of [ReconciliationBucket.entries]; amounts have scale 2. */
    fun view(
        docs: List<DocFact>,
        now: Instant,
    ): List<BucketTotal> {
        val uuids = HashSet<String>()
        docs.forEach { require(uuids.add(it.docUuid)) { "duplicate docUuid: '${it.docUuid}'" } }
        val byBucket =
            docs
                .mapNotNull { d ->
                    bucketOf(d, now)?.let { it to d.amount }
                }.groupBy({ it.first }, { it.second })
        return ReconciliationBucket.entries.map { b ->
            val amounts = byBucket[b].orEmpty()
            BucketTotal(
                b,
                amounts.size,
                amounts.fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }.setScale(2, RoundingMode.UNNECESSARY),
            )
        }
    }
}
