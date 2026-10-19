package com.dynokore.gst.access.application.keys

import com.dynokore.gst.access.domain.revocation.VerificationKey
import java.time.Clock

/** Read side of the token verification material. Keys are provisioned by the operations that rotate them. */
fun interface VerificationKeyStore {
    fun all(): List<VerificationKey>
}

/**
 * `getVerificationKeys`: the keys the gateway verifies tokens with offline (INV-API-4001 sibling, INV-TS-4002). One
 * consistent read of the store; a key outside its validity window at read time is dropped, in kid order, at most
 * [MAX_KEYS] (the contract's maxItems).
 */
class VerificationKeys(
    private val store: VerificationKeyStore,
    private val clock: Clock,
) {
    fun handle(): List<VerificationKey> {
        val now = clock.instant()
        val usable = store.all().filter { it.isUsableAt(now) }.sortedBy { it.kid }
        check(usable.size <= MAX_KEYS) { "more than $MAX_KEYS usable verification keys: rotate out the old ones" }
        return usable
    }

    companion object {
        const val MAX_KEYS = 10
    }
}
