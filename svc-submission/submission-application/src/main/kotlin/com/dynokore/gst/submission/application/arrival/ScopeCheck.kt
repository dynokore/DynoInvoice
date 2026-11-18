package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.scope.ScopeAgreement
import com.dynokore.gst.submission.domain.scope.ScopeCode
import com.dynokore.gst.submission.domain.scope.ScopeDecision
import com.dynokore.platform.tenancykernel.TenantId

/** The result of [ScopeCheck.check]. */
sealed interface ScopeOutcome {
    data object Proceed : ScopeOutcome

    data object Blocked : ScopeOutcome
}

/**
 * Finds a credit note whose scope verdict disagrees with its preceding document's and blocks its READY version with
 * SUB-013 (INV-REQ-722, INV-AC-24504). It opens no transaction (the caller's handler does, so the state change and the
 * raised code commit together), holds no transport or queue, and resolves nothing. Any input that cannot be compared
 * proceeds with no write; an unexpected row count throws (fail closed, the transaction rolls back).
 */
class ScopeCheck(
    private val facts: ScopeFacts,
    private val blocks: ScopeBlocks,
    private val raising: CodeRaising,
) {
    fun check(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): ScopeOutcome {
        val own = facts.of(tenant, docUuid) ?: return ScopeOutcome.Proceed
        if (own.kind != RowKind.CREDIT_NOTE) return ScopeOutcome.Proceed
        val verdict = own.verdict ?: return ScopeOutcome.Proceed
        val precedingDoc = own.precedingDocUuid ?: return ScopeOutcome.Proceed
        val preceding = facts.of(tenant, precedingDoc) ?: return ScopeOutcome.Proceed
        val precedingVerdict = preceding.verdict ?: return ScopeOutcome.Proceed
        if (ScopeAgreement.decide(verdict, precedingVerdict) !is ScopeDecision.Disagree) return ScopeOutcome.Proceed
        val changed = blocks.blockReady(tenant, docUuid, version)
        check(changed == 1) { "SUB-013 block of $docUuid version $version changed $changed rows" }
        raising.raise(tenant, docUuid, version, own.entityRef, own.periodRef, listOf(ScopeCode.SUB_013.code))
        return ScopeOutcome.Blocked
    }
}
