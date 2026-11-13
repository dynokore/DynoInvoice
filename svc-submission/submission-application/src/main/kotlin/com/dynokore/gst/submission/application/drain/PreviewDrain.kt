package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.resilience.Deadline
import java.math.BigDecimal
import java.math.RoundingMode

sealed interface PreviewResult {
    data class Previewed(
        val entityRef: String,
        val periodRef: String,
        val documentCount: Int,
        val totalAmount: BigDecimal,
    ) : PreviewResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : PreviewResult

    /** A selection or an amount is unavailable: no count and no partial total. */
    data class Unavailable(
        val cause: String,
    ) : PreviewResult
}

/**
 * Counts the versions a drain would attempt, by the same [DrainSelection], and sums taxable plus GST amount
 * (INV-REQ-1012). Writes and despatches nothing. The person key only: a MACHINE principal is forbidden.
 */
class PreviewDrain(
    private val authority: DrainAuthority,
    private val selection: DrainSelection,
    private val facts: RegisterListFactsStore,
) {
    fun run(
        request: DrainRequest,
        deadline: Deadline,
    ): PreviewResult {
        val denied = denial(authority, request, ::previewKey, deadline)
        if (denied != null) return PreviewResult.Forbidden(denied)
        val selected = selection.select(request) as? DrainSelected.Ranked ?: return PreviewResult.Unavailable(SELECTION)
        var total = BigDecimal.ZERO
        for (ranked in selected.items) {
            val listed = facts.facts(request.tenant, ranked.item.docUuid) ?: return PreviewResult.Unavailable(AMOUNT)
            total = total.add(listed.taxableAmount).add(listed.gstAmount)
        }
        val scaled =
            try {
                total.setScale(SCALE, RoundingMode.UNNECESSARY)
            } catch (_: ArithmeticException) {
                return PreviewResult.Unavailable(AMOUNT)
            }
        return PreviewResult.Previewed(request.entityRef, request.periodRef, selected.items.size, scaled)
    }

    private companion object {
        const val SCALE = 2
        const val SELECTION = "a due date, a margin or the backlog is unavailable"
        const val AMOUNT = "an amount is unavailable"
    }
}
