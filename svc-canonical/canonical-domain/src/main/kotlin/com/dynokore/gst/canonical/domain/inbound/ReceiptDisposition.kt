package com.dynokore.gst.canonical.domain.inbound

enum class DispositionReason { BUYER_REJECTED }

sealed interface DispositionDecision

/** The document is held with [CODE] until the buyer accepts again. */
data object HoldPending : DispositionDecision {
    const val CODE = "ING-013"
}

data object Proceed : DispositionDecision

data class NotSubmittable(
    val reason: DispositionReason,
) : DispositionDecision

/** INV-REQ-430, INV-REQ-557, INV-REQ-569: the buyer disposition of the bound document. Stateless. */
object ReceiptDisposition {
    fun decide(
        kind: InboundKind,
        carried: BuyerDisposition?,
        previous: BuyerDisposition?,
    ): DispositionDecision =
        when (kind) {
            InboundKind.POSTING -> {
                previous?.let(::outcome) ?: Proceed
            }

            InboundKind.RECEIPT -> {
                requireNotNull(carried) { "a receipt carries a buyerDisposition" }
                if (previous != null && (carried == BuyerDisposition.PENDING || carried == previous)) {
                    HoldPending
                } else {
                    outcome(carried)
                }
            }
        }

    private fun outcome(disposition: BuyerDisposition): DispositionDecision =
        when (disposition) {
            BuyerDisposition.PENDING -> HoldPending
            BuyerDisposition.ACCEPTED -> Proceed
            BuyerDisposition.REJECTED -> NotSubmittable(DispositionReason.BUYER_REJECTED)
        }
}
