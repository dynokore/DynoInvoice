package com.dynokore.gst.evidence.application.archive

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class RetainedArchiveVolumeTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun `the volume is the stored sum plus the object sum`() {
        val stored = StoredContentBytes { _, _ -> 100L + 200L }
        val objects = ObjectBytes { _, _ -> 1000L }
        RetainedArchiveVolume(stored, objects).bytesRetained(tenant, "E-1") shouldBe 1300L
    }

    @Test
    fun `an exception of the stored port propagates`() {
        val boom = IllegalArgumentException("db down")
        val volume = RetainedArchiveVolume({ _, _ -> throw boom }, { _, _ -> 5L })
        shouldThrow<IllegalArgumentException> { volume.bytesRetained(tenant, "E-1") } shouldBe boom
    }

    @Test
    fun `an exception of the object port propagates`() {
        val boom = IllegalArgumentException("store down")
        val volume = RetainedArchiveVolume({ _, _ -> 5L }, { _, _ -> throw boom })
        shouldThrow<IllegalArgumentException> { volume.bytesRetained(tenant, "E-1") } shouldBe boom
    }

    @Test
    fun `a sum beyond Long raises ArithmeticException`() {
        val volume = RetainedArchiveVolume({ _, _ -> Long.MAX_VALUE }, { _, _ -> 1L })
        shouldThrow<ArithmeticException> { volume.bytesRetained(tenant, "E-1") }
    }

    @Test
    fun `a negative half raises IllegalStateException`() {
        shouldThrow<IllegalStateException> {
            RetainedArchiveVolume({ _, _ -> -1L }, { _, _ -> 5L }).bytesRetained(tenant, "E-1")
        }
        shouldThrow<IllegalStateException> {
            RetainedArchiveVolume({ _, _ -> 5L }, { _, _ -> -1L }).bytesRetained(tenant, "E-1")
        }
    }

    @Test
    fun `the tenant and entity reach both ports`() {
        val seen = mutableListOf<String>()
        val volume =
            RetainedArchiveVolume(
                { t, e ->
                    seen += "s:$t:$e"
                    1L
                },
                { t, e ->
                    seen += "o:$t:$e"
                    2L
                },
            )
        volume.bytesRetained(tenant, "E-9") shouldBe 3L
        seen shouldBe listOf("s:$tenant:E-9", "o:$tenant:E-9")
    }
}
