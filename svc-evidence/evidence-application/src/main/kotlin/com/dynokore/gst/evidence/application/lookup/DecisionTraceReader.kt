package com.dynokore.gst.evidence.application.lookup

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** One pack version a decision ran under (contract `PackVersion`). */
data class PackVersionRef(
    val packIdentifier: String,
    val version: String,
)

/** One input of a decision (contract `DecisionTraceInput`); [businessTermId] is optional. */
data class TraceInput(
    val name: String,
    val value: String,
    val businessTermId: String?,
)

/** One decision of a trace (contract `DecisionTraceEntry`). */
data class DecisionEntry(
    val code: String,
    val ruleId: String,
    val packVersions: List<PackVersionRef>,
    val inputs: List<TraceInput>,
    val assertionText: String? = null,
)

/** What the store holds for (tenant, document, version): a trace that fits the contract, or one that does not. */
sealed interface StoredDecisionTrace {
    data class Valid(
        val recordedAt: Instant,
        val decisions: List<DecisionEntry>,
    ) : StoredDecisionTrace

    /** The stored content breaks the contract shape; never turned into a partial list (INV-Q-20711). */
    data object Malformed : StoredDecisionTrace
}

/** Reads the latest decision trace of one version in the caller's tenant transaction (INV-REQ-942). */
fun interface DecisionTraceReader {
    /** The row with the highest seq of (tenant, docUuid, version), or null when there is none. */
    fun latest(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): StoredDecisionTrace?
}
