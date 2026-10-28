package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.PeriodDigestObjectJson
import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.digest.periodDigest
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** The bytes of the object copy of a period digest (INV-Q-12048): deterministic, no `recordedAt`. */
class PeriodDigestObjectJsonTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val chains =
        listOf(
            ChainTail(DigestChainType.PAYLOAD, 3, "a".repeat(64)),
            ChainTail(DigestChainType.ACKNOWLEDGEMENT, 2, "b".repeat(64)),
            ChainTail(DigestChainType.DECISION_TRACE, 0, null),
        )

    private fun entry(recordedAt: Instant) =
        PeriodDigestEntry(
            "E-001",
            "2026-Q1",
            1,
            1,
            Instant.parse("2026-04-15T08:00:00Z"),
            recordedAt,
            "ret-1",
            chains,
            periodDigest("E-001", "2026-Q1", 1, chains),
        )

    @Test
    fun `two attempts of the same event with clocks one hour apart give identical bytes`() {
        val first = PeriodDigestObjectJson.bytes(tenant, entry(Instant.parse("2026-04-15T09:00:00Z")))
        val retry = PeriodDigestObjectJson.bytes(tenant, entry(Instant.parse("2026-04-15T10:00:00Z")))
        first.contentEquals(retry) shouldBe true
    }

    @Test
    fun `the top-level members are exactly the nine names`() {
        val node = JsonMapper.builder().build().readTree(PeriodDigestObjectJson.bytes(tenant, entry(Instant.EPOCH)))
        node.propertyNames().toSet() shouldBe
            setOf(
                "tenant",
                "entityRef",
                "period",
                "digestSequence",
                "returnVersion",
                "filedAt",
                "eventId",
                "digest",
                "chains",
            )
        node
            .get("chains")
            .values()
            .map { it.propertyNames().toSet() }
            .toSet() shouldBe
            setOf(setOf("type", "length", "tailHash"))
    }
}
