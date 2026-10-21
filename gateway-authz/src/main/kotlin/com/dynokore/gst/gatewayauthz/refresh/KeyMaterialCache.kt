package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.keys.VerificationKey
import com.dynokore.gst.gatewayauthz.revocation.RevocationList
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The key material held in memory (coordinator ruling INV-Q-3973 A): the verification keys, the revocation list and
 * the time the list was last read in full from access-svc. Each refresh swaps one immutable [State], so a request
 * never sees half a refresh. Loaded only after the keys and one full snapshot have arrived (INV-Q-4008 A).
 */
class KeyMaterialCache {
    data class State(
        val keys: List<VerificationKey>?,
        val revocations: RevocationList,
        val snapshotLoaded: Boolean,
        val listFreshAt: Instant?,
    )

    private val state = AtomicReference(State(null, RevocationList.EMPTY, snapshotLoaded = false, listFreshAt = null))

    fun current(): State = state.get()

    /** True once the keys and one full revocation-list snapshot are held; never false again afterwards. */
    val loaded: Boolean get() = current().let { it.keys != null && it.snapshotLoaded }

    fun keysRefreshed(keys: List<VerificationKey>) {
        state.updateAndGet { it.copy(keys = keys) }
    }

    /** A complete read of the list as of [readStartedAt]: the age of the list is measured from that instant. */
    fun revocationsRefreshed(
        list: RevocationList,
        fromSnapshot: Boolean,
        readStartedAt: Instant,
    ) {
        state.updateAndGet {
            it.copy(revocations = list, snapshotLoaded = it.snapshotLoaded || fromSnapshot, listFreshAt = readStartedAt)
        }
    }

    /** Drops entries past their expiresAt at [now], as access-svc does (INV-API-4001). */
    fun pruneExpired(now: Instant) {
        state.updateAndGet { it.copy(revocations = it.revocations.withoutExpiredAt(now)) }
    }
}
