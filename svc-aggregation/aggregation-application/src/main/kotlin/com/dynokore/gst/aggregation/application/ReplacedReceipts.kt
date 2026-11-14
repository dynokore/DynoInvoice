package com.dynokore.gst.aggregation.application

/** The answer of [ExcludeReplacedReceipt]. */
sealed interface ReplacedReceiptOutcome {
    /** The receipt was removed from an aggregate that was not transmitted. */
    data object Excluded : ReplacedReceiptOutcome

    /** The receipt is in no aggregate. */
    data object NotAggregated : ReplacedReceiptOutcome

    /** The aggregate of the receipt was already transmitted: nothing is changed (ING-014). */
    data object AlreadyTransmitted : ReplacedReceiptOutcome
}

/** The store side of the exclusion of an STI receipt that a full tax invoice replaced (INV-REQ-440). */
interface ReplacedReceiptStore {
    /** The outcome recorded for (entityRef, receiptNumber) by an earlier call; null when there is none. */
    fun recorded(
        entityRef: String,
        receiptNumber: String,
    ): ReplacedReceiptOutcome?

    /** The aggregate that holds the STI receipt of the entity, locked for the call; null when none holds it. */
    fun holder(
        entityRef: String,
        receiptNumber: String,
    ): StoredAggregate?

    /**
     * Records the exclusion permanently: the receipt leaves [holder] (when there is one) and the lines of [holder]
     * are rebuilt from what it still holds; a receipt not yet in an aggregate is never aggregated.
     */
    fun exclude(
        entityRef: String,
        receiptNumber: String,
        holder: StoredAggregate?,
    ): ReplacedReceiptOutcome
}

/** Runs [block] in one transaction. */
interface Atomically {
    fun <T> run(block: () -> T): T
}

/**
 * An STI receipt replaced by a full tax invoice is excluded from aggregation by its receipt number (INV-REQ-440,
 * FR-AE.54). From an aggregate not yet transmitted it is removed; from one already transmitted (or handed on for
 * submission, as [IntakeAggregate] reads "sent") nothing is removed and the caller flags the invoice with ING-014.
 * A repeat of the same (entityRef, receiptNumber) answers the original outcome.
 */
class ExcludeReplacedReceipt(
    private val store: ReplacedReceiptStore,
    private val atomically: Atomically,
) {
    fun execute(
        entityRef: String,
        receiptNumber: String,
    ): ReplacedReceiptOutcome =
        atomically.run {
            store.recorded(entityRef, receiptNumber) ?: run {
                val holder = store.holder(entityRef, receiptNumber)
                if (holder != null && (holder.transmittedAt != null || holder.handedOn)) {
                    ReplacedReceiptOutcome.AlreadyTransmitted
                } else {
                    store.exclude(entityRef, receiptNumber, holder)
                }
            }
        }
}
