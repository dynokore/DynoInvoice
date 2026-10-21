package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.revocation.RevocationEntry
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * One background refresh of the key material (coordinator ruling INV-Q-3973 A, INV-API-4001), run at least once a
 * second with one request in flight by [KeyMaterialRefreshSchedule]: the verification keys, then the revocation list,
 * a full snapshot until one has loaded and afterwards the entries above the held listVersion, a new snapshot whenever
 * access-svc answers with one. A multi-page read is applied only when its last page has arrived, at the listVersion of
 * that page. A failed read leaves the cache as it was (INV-Q-4008 A); the staleness bound then takes over
 * (owner ruling INV-Q-4021 A).
 */
class KeyMaterialRefresher(
    private val source: KeyMaterialSource,
    private val cache: KeyMaterialCache,
    private val clock: Clock,
) {
    /** Runs one refresh; returns false when either read failed (the cache is then unchanged for that read). */
    fun refreshOnce(): Boolean {
        val keysRead = attempt("getVerificationKeys") { cache.keysRefreshed(source.verificationKeys()) }
        val listRead = attempt("getRevocationList") { refreshRevocations() }
        cache.pruneExpired(clock.instant())
        return keysRead && listRead
    }

    private fun refreshRevocations() {
        val startedAt = clock.instant()
        val held = cache.current()
        val since = if (held.snapshotLoaded) held.revocations.listVersion else null
        val items = mutableListOf<RevocationEntry>()
        var page = source.revocations(since, null)
        val snapshot = page.snapshot
        items += page.items
        while (page.nextCursor != null) {
            page = source.revocations(since, page.nextCursor)
            items += page.items
        }
        val list =
            if (snapshot) {
                held.revocations.replacedBy(page.listVersion, items)
            } else {
                held.revocations.plus(page.listVersion, items)
            }
        cache.revocationsRefreshed(list, snapshot, startedAt)
    }

    @Suppress("TooGenericExceptionCaught") // INV-Q-3973 A: any failed read keeps the cache and must not stop the loop
    private fun attempt(
        operation: String,
        read: () -> Unit,
    ): Boolean =
        try {
            read()
            true
        } catch (failure: RuntimeException) {
            log.warn("{} failed, serving the cached key material: {}", operation, failure.javaClass.simpleName)
            false
        }

    private companion object {
        private val log = LoggerFactory.getLogger(KeyMaterialRefresher::class.java)
    }
}
