package com.dynokore.gst.evidence.application.lookup

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** The permission key of `getDecisionTrace`; a decision names a permission, never a role (INV-Q-10027). */
const val DOC_VIEW_CONTENT = "doc.view.content"

sealed interface DecisionTraceResult {
    data class Found(
        val docUuid: UUID,
        val version: Int,
        val recordedAt: Instant,
        val decisions: List<DecisionEntry>,
    ) : DecisionTraceResult

    /** No row for (tenant, document, version): unknown version, null-version row and other tenant's row alike. */
    data object NotFound : DecisionTraceResult

    /** The permission is not held, or the decision is unavailable (fail closed); no store is read. */
    data object Forbidden : DecisionTraceResult

    /** The stored content breaks the contract shape; never NotFound, never a partial list. */
    data object Malformed : DecisionTraceResult
}

/**
 * `getDecisionTrace` (INV-REQ-942, INV-AC-20775). Order: decide the permission, then read the latest trace of
 * (tenant, document, version). It writes no audit entry: the audited reads stay the four of the domain list.
 */
class DecisionTraceLookup(
    private val replica: AuthorityReplica,
    private val reader: DecisionTraceReader,
) {
    fun lookup(
        tenant: TenantId,
        principal: Principal,
        docUuid: UUID,
        version: Int,
    ): DecisionTraceResult {
        if (!replica.decideTenant(tenant, principal, DOC_VIEW_CONTENT).allowed) return DecisionTraceResult.Forbidden
        return when (val stored = reader.latest(tenant, docUuid, version)) {
            null -> {
                DecisionTraceResult.NotFound
            }

            StoredDecisionTrace.Malformed -> {
                DecisionTraceResult.Malformed
            }

            is StoredDecisionTrace.Valid -> {
                DecisionTraceResult.Found(docUuid, version, stored.recordedAt, stored.decisions)
            }
        }
    }
}
