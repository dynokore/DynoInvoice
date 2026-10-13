package com.dynokore.platform.serviceruntime.identity

/** STK-26: the callee's own side of INV-REQ-1202 — verifies a caller's workload token. */
fun interface WorkloadTokenVerifier {
    fun verify(token: WorkloadToken?): WorkloadVerification
}

/**
 * The callee's two outcomes (CS-08), never a thrown exception for an ordinary
 * refusal (INV-AC-2200): a missing or invalid token is as unremarkable to this
 * type as a valid one, not a programming error or infrastructure failure
 * (CS-14).
 */
sealed interface WorkloadVerification {
    /** [subject] is the calling workload's own identity (its service account) —
     * an identifier, safe to log (CS-34). */
    data class Verified(
        val subject: String,
    ) : WorkloadVerification

    /** [reason] is a diagnostic label, never the token or a claim value (CS-34)
     * — for example `"missing"`, `"invalid"`. */
    data class Refused(
        val reason: String,
    ) : WorkloadVerification
}
