package com.dynokore.gst.canonical.adapters

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * INV-AC-25593: the pinned platform libraries build platform-tenant envelopes for the rule pack events
 * RulePackStaged and RulePackActivated and refuse their look-alike types.
 */
class PlatformEnvelopeTypesTest {
    private val platformTypes =
        listOf(
            "com.dynokore.gst.platform.RulePackStaged.v1",
            "com.dynokore.gst.platform.RulePackActivated.v1",
        )

    private val lookAlikeTypes =
        listOf(
            "com.dynokore.gst.rules.RulePackStaged.v1",
            "com.dynokore.gst.rules.RulePackActivated.v1",
            "com.dynokore.gst.platform.RulePackStagedX.v1",
            "com.dynokore.gst.platform.rulePackActivated.v1",
        )

    private fun platformEnvelope(type: String) =
        Envelope(
            id = EventId("ev-1"),
            source = "/gst/rules-svc",
            type = type,
            subject = "pack-1",
            time = Instant.parse("2026-11-20T10:00:00Z"),
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "rules-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-25593 isPlatformType is true for the two rule pack platform types`() {
        platformTypes.forEach { Envelope.isPlatformType(it) shouldBe true }
    }

    @Test
    fun `INV-AC-25593 isPlatformType is false for the four look-alike types`() {
        lookAlikeTypes.forEach { Envelope.isPlatformType(it) shouldBe false }
    }

    @Test
    fun `INV-AC-25593 platform tenant envelope is constructed for each platform type`() {
        platformTypes.forEach { platformEnvelope(it).type shouldBe it }
    }

    @Test
    fun `INV-AC-25593 platform tenant envelope is refused for each look-alike type`() {
        lookAlikeTypes.forEach { type ->
            shouldThrow<IllegalArgumentException> { platformEnvelope(type) }
        }
    }
}
