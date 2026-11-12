package com.dynokore.platform.serviceruntime.http

/**
 * A downstream owner's refusal that a caller relays unchanged (coordinator ruling, round zq of 2026-11-12; INV-T-21194):
 * the owner's status, catalogue code and members. Absent optional members stay absent. A malformed refusal is refused
 * with [IllegalArgumentException]: a 5xx answer is never relayed (the caller maps it to the closed 503) and a 429
 * needs a `Retry-After` this type does not carry.
 */
data class RelayedRefusal(
    val status: Int,
    val code: String,
    val title: String,
    val severity: String? = null,
    val disposition: String? = null,
    val remediationOwner: String? = null,
    val detail: String? = null,
) {
    init {
        require(status in CLIENT_ERROR_RANGE && status != HTTP_TOO_MANY_REQUESTS) {
            "A relayed refusal has a client-error status other than 429, not $status"
        }
        require(CODE_PATTERN.matches(code)) { "A relayed refusal code matches ${CODE_PATTERN.pattern}, not '$code'" }
        require(title.isNotBlank()) { "A relayed refusal needs a title" }
        require(severity == null || severity in SEVERITIES) { "Severity is one of $SEVERITIES, not '$severity'" }
        require(disposition == null || disposition.isNotBlank()) { "A present disposition is not blank" }
        require(remediationOwner == null || remediationOwner.isNotBlank()) { "A present remediationOwner is not blank" }
    }

    private companion object {
        val CLIENT_ERROR_RANGE = 400..499
        const val HTTP_TOO_MANY_REQUESTS = 429
        val CODE_PATTERN = Regex("^[A-Z]{2,10}-[0-9]{3}$")
        val SEVERITIES = setOf("BLOCK", "HOLD", "WARN", "INFO")
    }
}
