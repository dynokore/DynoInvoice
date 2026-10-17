package com.dynokore.platform.adapterruntime

import java.time.Instant

/**
 * One event an upstream adapter emits, as the adapter contract sees it (INV-REQ-799).
 * Every element is nullable so that a missing one can be reported by
 * [UpstreamContractVerifier] instead of failing at construction.
 */
data class UpstreamEvent(
    val sourceKey: String?,
    val postingTimestamp: Instant?,
    val documentFunctionHint: String?,
    val lines: List<UpstreamLine>,
    val counterpartyRef: String?,
    val amendment: AmendmentIndicator?,
    val batchManifest: BatchManifest?,
)

/** The line-level tax attributes of an event, carried as the source gave them (INV-REQ-800). */
data class UpstreamLine(
    val taxCode: String?,
    val taxRate: String?,
    val taxAmount: String?,
)

/** Whether the event revises an earlier one, and the source key of that earlier event. */
data class AmendmentIndicator(
    val isAmendment: Boolean,
    val priorKey: String?,
)

/** The manifest of a file feed batch. */
data class BatchManifest(
    val batchId: String?,
    val expectedCount: Int?,
)

enum class FeedKind { EVENT_STREAM, FILE_FEED }

/** The seven elements of the adapter contract, one constant each (INV-REQ-799). */
enum class ContractElement {
    SOURCE_KEY,
    POSTING_TIMESTAMP,
    DOCUMENT_FUNCTION_HINT,
    LINE_TAX_ATTRIBUTES,
    COUNTERPARTY_REF,
    AMENDMENT_INDICATOR,
    BATCH_MANIFEST,
}

data class ContractViolation(
    val element: ContractElement,
    val detail: String,
)
