package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.events.CanonicalJson
import com.dynokore.gst.evidence.application.digest.DigestObjectConflict
import com.dynokore.gst.evidence.application.digest.DigestObjects
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * [DigestObjects] over the write-once [ObjectStore] port of evidence-store. The port has no exists call, so a key is
 * absent when `get` fails with the S3 `NoSuchKeyException` (matched by name: the SDK is not a compile dependency of
 * this module); any other failure propagates and the consumer retries (it fails closed).
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
        val present =
            try {
                store.get(ObjectKey(key))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // the SDK type is not on this classpath
                if (!generateSequence<Throwable>(
                        e,
                    ) { it.cause }.any { it.javaClass.simpleName == "NoSuchKeyException" }
                ) {
                    throw e
                }
                null
            }
        when {
            present == null -> store.put(ObjectKey(key), bytes, retainUntil)
            !present.contentEquals(bytes) -> throw DigestObjectConflict(key)
        }
    }
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
