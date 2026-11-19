package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The answer of the caller check of `resealBreakGlass`. */
enum class ResealAdmission {
    /** The caller is one of the identities stored as `approvedBy` of the unlock. */
    MEMBER,

    /** No unlock of that reference exists in the bound tenant. */
    UNKNOWN,

    /** The unlock exists and the caller is not in its pair, or the lookup failed (fail closed). */
    OUTSIDER,
}

/** The caller check of `resealBreakGlass`: membership of the unlocking pair, decided per request. */
fun interface ResealPairCheck {
    fun admit(
        actor: String,
        unlockRef: String,
    ): ResealAdmission
}

/**
 * Admits the caller when it equals, by string equality (no trimming, no case folding), one identity stored as
 * `approvedBy` of the unlock in the bound tenant. Membership is the only rule: no role and no permission is read
 * here (owner ruling 2026-11-18). A lookup that throws is [ResealAdmission.OUTSIDER]; an exception never admits.
 */
class StoredPairResealCheck(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
) : ResealPairCheck {
    override fun admit(
        actor: String,
        unlockRef: String,
    ): ResealAdmission =
        runCatching {
            val session = tx.inTransaction { sessions.find(unlockRef) }
            when {
                session == null -> ResealAdmission.UNKNOWN
                session.approvedBy.any { it == actor } -> ResealAdmission.MEMBER
                else -> ResealAdmission.OUTSIDER
            }
        }.getOrDefault(ResealAdmission.OUTSIDER)
}
