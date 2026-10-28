package com.dynokore.gst.submission.domain.scope

/** The members of the `scopeVerdict` enum of canonical/openapi.yaml that this component routes (INV-Q-7127 A). */
const val SUBMITTABLE = "SUBMITTABLE"
const val NOT_SUBMITTABLE = "NOT_SUBMITTABLE"
const val PRE_OBLIGATION = "PRE_OBLIGATION"

/** Why a document is not submitted; distinct from the contract-mirroring NotSubmittableReason (INV-Q-12394 A). */
enum class NotSubmittableRouteReason { OUT_OF_SCOPE_OR_EXCLUDED, PRE_OBLIGATION }

/**
 * The route of a document by its scope verdict. Only [Submittable] may lead to a queue entry; [NotSubmittable] and
 * [Unrecognised] never do (fail closed, INV-REQ-1087).
 */
sealed interface ScopeRoute {
    data object Submittable : ScopeRoute

    data class NotSubmittable(
        val reason: NotSubmittableRouteReason,
    ) : ScopeRoute

    /** A verdict this component does not know: held back, not submitted, reported to the caller. */
    data class Unrecognised(
        val verdict: String,
    ) : ScopeRoute
}

/**
 * Routes a document by its scope verdict alone (INV-Q-7127 A, INV-REQ-1087). The input has no channel, origin or
 * backfill parameter, so a live and a backfilled document with the same verdict route the same way. Pure: no state,
 * no clock; the match is exact (no trimming, no case folding).
 */
object ScopeVerdictRouting {
    fun decide(scopeVerdict: String): ScopeRoute =
        when (scopeVerdict) {
            SUBMITTABLE -> ScopeRoute.Submittable
            NOT_SUBMITTABLE -> ScopeRoute.NotSubmittable(NotSubmittableRouteReason.OUT_OF_SCOPE_OR_EXCLUDED)
            PRE_OBLIGATION -> ScopeRoute.NotSubmittable(NotSubmittableRouteReason.PRE_OBLIGATION)
            else -> ScopeRoute.Unrecognised(scopeVerdict)
        }
}
