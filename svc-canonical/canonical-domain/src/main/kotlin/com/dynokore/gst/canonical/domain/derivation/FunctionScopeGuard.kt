package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.document.ScopeVerdict

/**
 * Decides the document scope verdict from the classified function and the verdict the lines gave.
 * INV-REQ-78 behavior 2, INV-REQ-75 behavior 1; INV-AC-15891, INV-AC-15892. Exhaustive `when`, no default branch.
 */
object FunctionScopeGuard {
    fun decide(
        function: UpstreamFunction,
        byLines: ScopeVerdict,
    ): ScopeVerdict =
        when (function) {
            UpstreamFunction.NOT_A_BILL -> ScopeVerdict.OUT_OF_SCOPE
            UpstreamFunction.BILL_FOR_PAYMENT -> byLines
            UpstreamFunction.ADJUSTMENT -> byLines
        }
}
