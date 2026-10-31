package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.application.archive.ObjectBytes
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * [ObjectBytes] over [ObjectStore.bytesUnder] (INV-Q-12679 A): the bytes of every object the service holds for one
 * (tenant, entity), which is every object under the whole-segment prefix of the digest object keys. A store that
 * cannot answer raises, so the measurement fails closed and publishes nothing.
 */
class ObjectStoreBytes(
    private val store: ObjectStore,
) : ObjectBytes {
    override fun bytes(
        tenant: TenantId,
        entityRef: String,
    ): Long {
        val total = store.bytesUnder(prefix(tenant, entityRef))
        check(total >= 0) { "the object store bytes are negative" }
        return total
    }

    companion object {
        private const val PERIOD = "period"
        private const val SEQUENCE = 1

        /**
         * The prefix of every digest object key of (tenant, entity): the key [PeriodDigestObjectJson.key] builds for a
         * sample entry, less the segments that follow the entity. There is no second key template here.
         */
        fun prefix(
            tenant: TenantId,
            entityRef: String,
        ): ObjectKeyPrefix {
            val sample =
                PeriodDigestEntry(entityRef, PERIOD, SEQUENCE, 0, Instant.EPOCH, Instant.EPOCH, "", emptyList(), "")
            val key = PeriodDigestObjectJson.key(tenant, sample)
            val tail = "$PERIOD/$SEQUENCE.json"
            check(key.endsWith("/$tail")) { "the digest object key has an unexpected shape" }
            return ObjectKeyPrefix(key.removeSuffix(tail))
        }
    }
}
