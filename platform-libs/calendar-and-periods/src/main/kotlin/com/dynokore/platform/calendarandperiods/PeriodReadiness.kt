package com.dynokore.platform.calendarandperiods

/** One completeness control of a period (its inputs are produced by Wave 3 services): a code and whether it holds. */
data class CompletenessControl(
    val code: String,
    val complete: Boolean,
) {
    init {
        require(code.isNotBlank()) { "a completeness control has a code" }
    }
}

/** The verdict of period readiness gating. */
sealed interface ReadinessVerdict {
    data object Ready : ReadinessVerdict

    /** The period is not ready: [blockingCodes] are the incomplete controls, in input order. */
    data class Blocked(
        val blockingCodes: List<String>,
    ) : ReadinessVerdict
}

/**
 * Period readiness gating: a pure function of a period's completeness controls. Any incomplete control blocks and is
 * named by its code. An empty list is refused rather than read as ready, so a missing control set can never open a
 * period.
 */
fun gatePeriodReadiness(controls: List<CompletenessControl>): ReadinessVerdict {
    require(controls.isNotEmpty()) { "readiness is gated on at least one completeness control" }
    val blocking = controls.filter { !it.complete }.map { it.code }
    return if (blocking.isEmpty()) ReadinessVerdict.Ready else ReadinessVerdict.Blocked(blocking)
}
