package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.events.CanonicalJson
import com.dynokore.gst.evidence.application.digest.DigestObjectConflict
import com.dynokore.gst.evidence.application.digest.DigestObjects
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.gst.evidence.application.integrity.DigestCopies
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.tenancykernel.TenantId
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * [DigestObjects] over the write-once [ObjectStore] port of evidence-store. The port has no exists call, so a key is
 * absent only when `get` fails with the S3 SDK's [NoSuchKeyException] on the cause chain (INV-Q-12051); any other
 * failure propagates and the consumer retries (it fails closed).
 */
class ObjectStoreDigestObjects(
    private val store: ObjectStore,
) : DigestObjects {
    override fun putOrVerify(
        tenant: TenantId,
        entry: PeriodDigestEntry,
        retainUntil: Instant,
    ) {
        val key = PeriodDigestObjectJson.key(tenant, entry)
        val bytes = PeriodDigestObjectJson.bytes(tenant, entry)
        val present = readIfPresent(store, key)
        when {
            present == null -> store.put(ObjectKey(key), bytes, retainUntil)
            !present.contentEquals(bytes) -> throw DigestObjectConflict(key)
        }
    }
}

/**
 * The bytes under [key], or null when the key is absent. The port has no exists call, so a key is absent only when
 * `get` fails with the S3 SDK's [NoSuchKeyException] on the cause chain (INV-Q-12051); any other failure propagates.
 */
private fun readIfPresent(
    store: ObjectStore,
    key: String,
): ByteArray? =
    try {
        store.get(ObjectKey(key))
    } catch (
        @Suppress("TooGenericExceptionCaught") e: RuntimeException,
    ) {
        if (generateSequence<Throwable>(e) { it.cause }.none { it is NoSuchKeyException }) throw e
        null
    }

/**
 * [DigestCopies] over the same [ObjectStore]: the copy matches when it holds the bytes the stored rows give. A copy
 * that is absent or altered does not match; a copy that cannot be read raises, so the verification fails closed.
 */
class ObjectStoreDigestCopies(
    private val store: ObjectStore,
) : DigestCopies {
    override fun matches(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    ): Boolean =
        readIfPresent(store, PeriodDigestObjectJson.key(tenant, entry))
            ?.contentEquals(PeriodDigestObjectJson.bytes(tenant, entry)) == true
}

/** The key and the content of the object copy of a period digest (INV-Q-7251 A (3)). */
object PeriodDigestObjectJson {
    private val mapper = JsonMapper.builder().build()

    fun key(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    ): String = "period-digests/${tenant.value}/${entry.entityRef}/${entry.period}/${entry.digestSequence}.json"

    /**
     * The canonical JSON of the digest row and its three chain rows. `recordedAt` is left out: it is the clock of the
     * attempt, so a retry after a rolled-back attempt would otherwise differ from the object it left behind.
     */
    fun bytes(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    ): ByteArray {
        val node = mapper.createObjectNode()
        node.put("tenant", tenant.value.toString())
        node.put("entityRef", entry.entityRef)
        node.put("period", entry.period)
        node.put("digestSequence", entry.digestSequence)
        node.put("returnVersion", entry.returnVersion)
        node.put("filedAt", entry.filedAt.toString())
        node.put("eventId", entry.eventId)
        node.put("digest", entry.digest)
        val chains = node.putArray("chains")
        entry.chains.forEach {
            chains
                .addObject()
                .put("type", it.type.name)
                .put("length", it.length)
                .put("tailHash", it.tailText)
        }
        return CanonicalJson.bytes(node)
    }
}
