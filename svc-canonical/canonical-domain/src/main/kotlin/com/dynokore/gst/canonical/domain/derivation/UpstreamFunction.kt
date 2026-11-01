package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.netting.DocumentFunction

/**
 * The function vocabulary of rules-svc (`EvaluateDocumentRequest.documentFunction`, scope pack `upstreamFunction`).
 * INV-REQ-371 behavior 6; INV-AC-13668. It holds no document type code: that stays scope-pack data (INV-REQ-658).
 */
enum class UpstreamFunction { BILL_FOR_PAYMENT, ADJUSTMENT, NOT_A_BILL }

/** The one fixed mapping of the six caller values; total by exhaustive `when` (no default branch). */
fun DocumentFunction.toUpstreamFunction(): UpstreamFunction =
    when (this) {
        DocumentFunction.INVOICE -> UpstreamFunction.BILL_FOR_PAYMENT
        DocumentFunction.BILL_FOR_PAYMENT -> UpstreamFunction.BILL_FOR_PAYMENT
        DocumentFunction.CREDIT_NOTE -> UpstreamFunction.ADJUSTMENT
        DocumentFunction.DEBIT_NOTE -> UpstreamFunction.ADJUSTMENT
        DocumentFunction.ADJUSTMENT -> UpstreamFunction.ADJUSTMENT
        DocumentFunction.NOT_A_BILL -> UpstreamFunction.NOT_A_BILL
    }
