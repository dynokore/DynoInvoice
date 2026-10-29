package com.dynokore.platform.evidencestore

import java.time.Instant

/** What [ObjectStore.put] hands back: the key and the retention date the store committed to. */
data class ObjectRef(
    val key: ObjectKey,
    val retainUntil: Instant,
)

/**
 * Object storage under object lock (STK-20, FR-9.10): compliance-mode
 * retention, so that not even the storage administrator can delete or
 * shorten it before [ObjectRef.retainUntil]. There is deliberately no
 * `delete` or `update` method — the same structural guarantee [EvidenceLedger]
 * uses (AGT-12: nothing here builds a way to delete evidence); the underlying
 * bucket's own object-lock configuration is what actually refuses a delete
 * attempted outside this port altogether — the conformance probe STK-20
 * names: "put, retain, refuse delete".
 */
interface ObjectStore {
    fun put(
        key: ObjectKey,
        bytes: ByteArray,
        retainUntil: Instant,
    ): ObjectRef

    fun get(key: ObjectKey): ByteArray

    /**
     * The total size in bytes of every object whose key starts with [prefix], over the
     * whole listing; 0 when there is none. Read-only, so the port stays append-only.
     */
    fun bytesUnder(prefix: ObjectKeyPrefix): Long =
        throw UnsupportedOperationException("${this.javaClass.name} does not implement bytesUnder")
}
