package com.dynokore.platform.rulepackkernel

/** The result of [PackVerifier.verify] — never an exception for an untrusted or malformed artefact. */
sealed interface PackVerificationOutcome {
    /**
     * The artefact's checksums and detached CMS signature both check out against
     * [trustedAnchorLabel] (STK-13, including a successor key).
     */
    data class Verified(
        val manifest: PackManifest,
        val trustedAnchorLabel: String,
    ) : PackVerificationOutcome

    /** Refused; never partially trusted. */
    data class Rejected(
        val reason: PackRejectionReason,
        val detail: String,
    ) : PackVerificationOutcome
}
