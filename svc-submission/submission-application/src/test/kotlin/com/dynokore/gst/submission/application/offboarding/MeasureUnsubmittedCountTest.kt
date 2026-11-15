package com.dynokore.gst.submission.application.offboarding

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MeasureUnsubmittedCountTest {
    private val instant = Instant.parse("2026-11-19T10:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val tenant = TenantId(UUID.randomUUID())

    @Test
    fun `INV-AC-22871 the source receives exactly the tenant passed in one call`() {
        val seen = mutableListOf<TenantId>()
        MeasureUnsubmittedCount(
            { t ->
                seen += t
                emptyMap()
            },
            clock,
        ).execute(tenant)
        seen shouldBe listOf(tenant)
    }

    @Test
    fun `INV-AC-22871 measuredAt equals the clock instant`() {
        MeasureUnsubmittedCount({ emptyMap() }, clock).execute(tenant).measuredAt shouldBe instant
    }

    @Test
    fun `INV-AC-22871 total equals the sum of the entity counts and entities are ordered by entityRef`() {
        val m = MeasureUnsubmittedCount({ linkedMapOf("E2" to 2, "E1" to 3) }, clock).execute(tenant)
        m.total shouldBe 5
        m.entities shouldBe listOf(EntityCount("E1", 3), EntityCount("E2", 2))
    }

    @Test
    fun `INV-AC-22871 an empty map gives total 0 and an empty list`() {
        val m = MeasureUnsubmittedCount({ emptyMap() }, clock).execute(tenant)
        m.total shouldBe 0
        m.entities shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22871 a source that throws makes execute throw the same exception instance`() {
        val boom = IllegalStateException("down")
        val thrown = runCatching { MeasureUnsubmittedCount({ throw boom }, clock).execute(tenant) }.exceptionOrNull()
        (thrown === boom) shouldBe true
    }
}
