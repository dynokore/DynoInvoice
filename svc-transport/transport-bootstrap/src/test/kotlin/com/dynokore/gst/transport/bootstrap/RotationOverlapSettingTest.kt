package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.IrasEnvironment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/** The overlap window of a rotation is a property with no default; zero or less is refused at start (INV-REQ-837). */
class RotationOverlapSettingTest {
    private fun properties(window: Duration?) = ApProperties(IrasEnvironment.SANDBOX, rotationOverlap = window)

    @Test
    fun `the window has no default`() {
        properties(null).rotationOverlap shouldBe null
        properties(Duration.ofHours(24)).rotationOverlap shouldBe Duration.ofHours(24)
        properties(Duration.ofNanos(1)).rotationOverlap shouldBe Duration.ofNanos(1)
    }

    @Test
    fun `zero and negative windows are refused`() {
        for (window in listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(-1), Duration.ofDays(-1))) {
            shouldThrow<IllegalArgumentException> { properties(window) }.message shouldContain "rotation-overlap"
        }
    }

    @Test
    fun `a service started with a zero or negative window fails to start, an unset one starts`(
        @TempDir dir: Path,
    ) {
        for (value in listOf("0s", "0", "-5m", "PT0S")) {
            TransportContext.offline(dir).withPropertyValues("transport.ap.rotation-overlap=$value").run {
                (it.startupFailure != null) shouldBe true
            }
        }
        TransportContext.offline(dir).run { (it.startupFailure == null) shouldBe true }
        TransportContext.offline(dir).withPropertyValues("transport.ap.rotation-overlap=24h").run {
            (it.startupFailure == null) shouldBe true
        }
    }
}
