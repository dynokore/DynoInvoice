package com.dynokore.platform.adapterruntime

import com.dynokore.platform.adapterruntime.ContractElement.AMENDMENT_INDICATOR
import com.dynokore.platform.adapterruntime.ContractElement.BATCH_MANIFEST
import com.dynokore.platform.adapterruntime.ContractElement.COUNTERPARTY_REF
import com.dynokore.platform.adapterruntime.ContractElement.DOCUMENT_FUNCTION_HINT
import com.dynokore.platform.adapterruntime.ContractElement.LINE_TAX_ATTRIBUTES
import com.dynokore.platform.adapterruntime.ContractElement.POSTING_TIMESTAMP
import com.dynokore.platform.adapterruntime.ContractElement.SOURCE_KEY

/**
 * Checks that an [UpstreamEvent] carries the seven elements of the adapter contract
 * (INV-REQ-799, INV-AC-1853). Pure: it reports, and never repairs or defaults a value.
 */
object UpstreamContractVerifier {
    fun verify(
        event: UpstreamEvent,
        feed: FeedKind,
    ): List<ContractViolation> {
        val violations = mutableListOf<ContractViolation>()

        fun report(
            element: ContractElement,
            detail: String,
        ) {
            violations += ContractViolation(element, detail)
        }

        if (event.sourceKey.isNullOrBlank()) report(SOURCE_KEY, "source key is missing or blank")
        if (event.postingTimestamp == null) report(POSTING_TIMESTAMP, "posting timestamp is missing")
        if (event.documentFunctionHint.isNullOrBlank()) {
            report(DOCUMENT_FUNCTION_HINT, "document function hint is missing or blank")
        }
        verifyLines(event.lines)?.let { report(LINE_TAX_ATTRIBUTES, it) }
        if (event.counterpartyRef.isNullOrBlank()) {
            report(COUNTERPARTY_REF, "counterparty reference is missing or blank")
        }
        verifyAmendment(event.amendment)?.let { report(AMENDMENT_INDICATOR, it) }
        if (feed == FeedKind.FILE_FEED) verifyManifest(event.batchManifest)?.let { report(BATCH_MANIFEST, it) }
        return violations
    }

    private fun verifyLines(lines: List<UpstreamLine>): String? {
        val bad =
            lines.withIndex().filter { (_, l) ->
                l.taxCode.isNullOrBlank() || l.taxRate.isNullOrBlank() || l.taxAmount.isNullOrBlank()
            }
        return when {
            lines.isEmpty() -> "no line carries tax attributes"
            bad.isNotEmpty() -> "line(s) ${bad.joinToString { it.index.toString() }} lack taxCode, taxRate or taxAmount"
            else -> null
        }
    }

    private fun verifyAmendment(amendment: AmendmentIndicator?): String? =
        when {
            amendment == null -> "amendment indicator is missing"
            amendment.isAmendment && amendment.priorKey.isNullOrBlank() -> "amendment has no prior key"
            !amendment.isAmendment && amendment.priorKey != null -> "non-amendment carries a prior key"
            else -> null
        }

    private fun verifyManifest(manifest: BatchManifest?): String? =
        when {
            manifest == null -> {
                "file feed has no batch manifest"
            }

            manifest.batchId.isNullOrBlank() -> {
                "batch manifest has no batch id"
            }

            manifest.expectedCount == null || manifest.expectedCount < 1 -> {
                "batch manifest expected count is missing or below 1"
            }

            else -> {
                null
            }
        }
}
