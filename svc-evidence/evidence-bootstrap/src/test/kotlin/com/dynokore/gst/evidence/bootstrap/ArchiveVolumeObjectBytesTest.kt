package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreBytes
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestObjects
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Instant
import java.util.UUID

/** `ObjectStoreBytes` over a fake [ObjectStore] (INV-Q-12679 A): the sum of one (tenant, entity) prefix only. */
class ArchiveVolumeObjectBytesTest {
    private class Fake(
        sizes: Map<String, Int> = emptyMap(),
    ) : ObjectStore {
        val stored = sizes.mapValues { ByteArray(it.value) }.toMutableMap()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            stored[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray =
            stored[key.value] ?: throw NoSuchKeyException.builder().message("no such key").build()

        override fun bytesUnder(prefix: ObjectKeyPrefix): Long =
            stored.filterKeys { it.startsWith(prefix.value) }.values.sumOf { it.size.toLong() }
    }

    /** A store that keeps the default body of `bytesUnder`, which refuses. */
    private class Refusing : ObjectStore {
        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef = ObjectRef(key, retainUntil)

        override fun get(key: ObjectKey): ByteArray = ByteArray(0)
    }

    private val tenant = TenantId(UUID.randomUUID())
    private val otherTenant = TenantId(UUID.randomUUID())

    private fun key(
        who: TenantId,
        entity: String,
        name: String,
    ) = "period-digests/${who.value}/$entity/2026-Q3/$name.json"

    private fun entry(entity: String) =
        PeriodDigestEntry(entity, "2026-Q3", 1, 1, Instant.EPOCH, Instant.EPOCH, "ev", emptyList(), "d")

    @Test
    fun `the sum counts one entity and not the other tenant or the look-alike entity E10`() {
        val store =
            Fake(
                mapOf(
                    key(tenant, "E1", "1") to 1000,
                    key(otherTenant, "E1", "1") to 5000,
                    key(tenant, "E10", "1") to 7000,
                ),
            )
        ObjectStoreBytes(store).bytes(tenant, "E1") shouldBe 1000L
        ObjectStoreBytes(store).bytes(tenant, "E10") shouldBe 7000L
        ObjectStoreBytes(store).bytes(tenant, "E2") shouldBe 0L
    }

    @Test
    fun `the prefix is the prefix of the key the digest object writer wrote and ends with a slash`() {
        val store = Fake()
        ObjectStoreDigestObjects(store).putOrVerify(tenant, entry("E1"), Instant.EPOCH)
        val written = store.stored.keys.single()
        val prefix = ObjectStoreBytes.prefix(tenant, "E1")
        prefix.value shouldBe "period-digests/${tenant.value}/E1/"
        written.startsWith(prefix.value) shouldBe true
        written.startsWith(ObjectStoreBytes.prefix(tenant, "E").value) shouldBe false
        ObjectStoreBytes(store).bytes(tenant, "E1") shouldBe
            store.stored
                .getValue(written)
                .size
                .toLong()
    }

    @Test
    fun `a store that keeps the default body refuses and the sum fails`() {
        shouldThrow<UnsupportedOperationException> { ObjectStoreBytes(Refusing()).bytes(tenant, "E1") }
    }

    @Test
    fun `a negative answer of the store fails the sum`() {
        val negative =
            object : ObjectStore by Refusing() {
                override fun bytesUnder(prefix: ObjectKeyPrefix): Long = -1
            }
        shouldThrow<IllegalStateException> { ObjectStoreBytes(negative).bytes(tenant, "E1") }
    }
}
