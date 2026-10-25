package com.dynokore.gst.evidence.domain.chain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class AuditChainKeyTest {
    private fun period(time: Instant) = AuditChainKey.of("ENT-3F2A9C", time).periodRef

    @Test
    fun `the last second of October is October and the first of November is November`() {
        period(Instant.parse("2026-10-31T23:59:59Z")) shouldBe "AUDIT-2026-10"
        period(Instant.parse("2026-11-01T00:00:00Z")) shouldBe "AUDIT-2026-11"
    }

    @Test
    fun `the month is read in UTC and not in the offset of the instant`() {
        period(OffsetDateTime.parse("2026-11-01T01:00:00+08:00").toInstant()) shouldBe "AUDIT-2026-10"
    }

    @Test
    fun `an entity reference is kept as given, the nil UUID and the texts TENANT and tenant included`() {
        val nil = "00000000-0000-0000-0000-000000000000"
        AuditChainKey.of(nil, Instant.EPOCH).entityRef shouldBe nil
        AuditChainKey.of("TENANT", Instant.EPOCH).entityRef shouldBe "TENANT"
        AuditChainKey.of("ENT-3F2A9C", Instant.EPOCH).entityRef shouldBe "ENT-3F2A9C"
        AuditChainKey.of("tenant", Instant.EPOCH).entityRef shouldBe "tenant"
        AuditChainKey.of("TENANT ", Instant.EPOCH).entityRef shouldBe "TENANT "
    }

    @Test
    fun `a blank entity reference is refused`() {
        shouldThrow<IllegalArgumentException> { AuditChainKey.of("", Instant.EPOCH) }
        shouldThrow<IllegalArgumentException> { AuditChainKey.of(" ", Instant.EPOCH) }
    }

    @Test
    fun `the period text is 13 characters`() {
        period(Instant.parse("2026-10-31T23:59:59Z")).length shouldBe 13
    }
}
