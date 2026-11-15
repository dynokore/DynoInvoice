package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ObjectStorePackageStore
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.RetentionPeriod
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-22948: the package store keeps a written package under its tenant and offboarding reference. */
class ObjectStorePackageStoreTest {
    private class Memory : ObjectStore {
        val objects = mutableMapOf<String, ByteArray>()
        val retention = mutableMapOf<String, Instant>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            objects[key.value] = bytes
            retention[key.value] = retainUntil
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = objects.getValue(key.value)
    }

    private val t1 = TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val files =
        PackageFiles(
            mapOf(
                "manifest-sha256.txt" to "abc  data/x\n".toByteArray(),
                "bagit.txt" to "BagIt-Version: 1.0\n".toByteArray(),
                "data/transmitted-xml/1-aa.xml" to byteArrayOf(0, 1, 2, -1),
            ),
        )

    private var supplied = 0

    private fun store(
        memory: Memory,
        scheme: String = "s3",
        years: Int = 5,
    ) = ObjectStorePackageStore(
        memory,
        scheme,
        Clock.fixed(now, ZoneOffset.UTC),
    ) {
        supplied++
        RetentionPeriod(years)
    }

    @Test
    fun `INV-AC-22948 the store writes every file under the prefix of T1 and OFF-1 and reads the bytes back`() {
        val memory = Memory()
        val ref = store(memory).store(t1, "OFF-1", files)
        val prefix = "exports/offboarding/${t1.value}/OFF-1/"
        memory.objects.keys.toSet() shouldBe files.paths.map { prefix + it }.toSet()
        files.paths.forEach { memory.get(ObjectKey(prefix + it)).contentEquals(files.bytes(it)) shouldBe true }
        ref shouldBe "s3://${prefix}manifest-sha256.txt"
        memory.retention.values.toSet() shouldBe setOf(Instant.parse("2031-11-15T00:00:00Z"))
    }

    @Test
    fun `INV-AC-22948 nothing is written under another tenant or another offboarding reference`() {
        val memory = Memory()
        store(memory).store(t1, "OFF-1", files)
        val other = TenantId(UUID.fromString("22222222-2222-4222-8222-222222222222"))
        memory.objects.keys.none { it.startsWith("exports/offboarding/${other.value}/") } shouldBe true
        memory.objects.keys.none { it.startsWith("exports/offboarding/${t1.value}/OFF-2/") } shouldBe true
        memory.objects.keys.all { it.startsWith("exports/offboarding/${t1.value}/OFF-1/") } shouldBe true
    }

    @Test
    fun `INV-AC-22948 an offboarding reference that is not one safe segment is refused and nothing is written`() {
        val memory = Memory()
        shouldThrow<IllegalArgumentException> { store(memory).store(t1, "../OFF-1", files) }
        shouldThrow<IllegalArgumentException> { store(memory).store(t1, "a/b", files) }
        memory.objects.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-22948 retainUntil is the instant plus the supplied years and the supplier is called once per store`() {
        val memory = Memory()
        val subject = store(memory, years = 7)
        subject.store(t1, "OFF-1", files)
        memory.retention.size shouldBe files.paths.size
        memory.retention.values.toSet() shouldBe setOf(Instant.parse("2033-11-15T00:00:00Z"))
        supplied shouldBe 1
        subject.store(t1, "OFF-2", files)
        supplied shouldBe 2
    }

    @Test
    fun `INV-AC-22948 the manifest reference starts with the scheme passed to the constructor`() {
        val ref = store(Memory(), scheme = "gcs").store(t1, "OFF-1", files)
        ref.startsWith("gcs://exports/offboarding/${t1.value}/OFF-1/") shouldBe true
    }
}
