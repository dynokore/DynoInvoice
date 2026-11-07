package com.dynokore.gst.consoleBff.domain.queue

data class QueueRow(
    val exceptionId: String,
    val entityRef: String,
    val code: String,
    val remediationOwner: String,
)

sealed interface ScopeDecision {
    data class Entities(
        val entityRefs: List<String>,
    ) : ScopeDecision

    data class Refused(
        val entityRef: String,
    ) : ScopeDecision
}

/**
 * Decides the entities of the cross-entity exception queue. [scope] is the set of entityRef values the caller
 * may read, already decided per request by AuthorityReplica.decide; this function reads no role and no permission.
 * Deny by default: a request outside the scope is refused, an empty scope yields no entity.
 */
fun decideScope(
    requestedEntityRef: String?,
    scope: Set<String>,
): ScopeDecision =
    when {
        requestedEntityRef == null -> ScopeDecision.Entities(scope.sorted())
        requestedEntityRef in scope -> ScopeDecision.Entities(listOf(requestedEntityRef))
        else -> ScopeDecision.Refused(requestedEntityRef)
    }

/** Keeps the rows whose entityRef equals a scope member, in input order, each row unchanged. */
fun keepInScope(
    rows: List<QueueRow>,
    scope: Set<String>,
): List<QueueRow> = rows.filter { it.entityRef in scope }
