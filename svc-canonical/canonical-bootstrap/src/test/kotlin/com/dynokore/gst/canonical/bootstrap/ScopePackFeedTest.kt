package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.ScopePackReader
import com.dynokore.gst.canonical.adapters.persistence.ScopePackActivation
import com.dynokore.gst.canonical.adapters.persistence.ScopePackRow
import com.dynokore.gst.canonical.adapters.persistence.StagedScopePack
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-25429: the RulePackStaged and RulePackActivated consumers over fake sinks, and their wiring (P109). The
 * handlers are driven by `apply(dataJson, tenant)`, and by a real platform-tenant `Envelope` through `handle(event)`.
 */
class ScopePackFeedTest {
    private class FakeSinks :
        ScopePackStagedSink,
        ScopePackActivationSink {
        val staged = mutableListOf<StagedScopePack>()
        val activations = mutableListOf<ScopePackActivation>()
        var fresh = true

        override fun stage(pack: StagedScopePack): Boolean {
            staged += pack
            return fresh
        }

        override fun activate(activation: ScopePackActivation): Boolean {
            activations += activation
            return fresh
        }
    }

    private fun stagedData(
        packType: String = "SCOPE",
        code: String = "\"380\"",
        version: String? = "\"2026.1.0\"",
    ) = "{\"packIdentifier\":\"gst-scope\",\"packType\":\"$packType\"" +
        (if (version != null) ",\"version\":$version" else "") +
        ",\"effectiveFrom\":\"2026-12-01\",\"artefact\":{\"manifest\":{},\"checksums\":{},\"signature\":\"c2ln\"," +
        "\"contents\":{\"classificationTable\":[" +
        "{\"documentFunction\":\"BILL_FOR_PAYMENT\",\"documentTypeCode\":$code}," +
        "{\"documentFunction\":\"NOT_A_BILL\"}]}}}"

    private val activatedData =
        "{\"packIdentifier\":\"gst-scope\",\"version\":\"2026.1.0\",\"effectiveFrom\":\"2026-12-01\"}"

    private fun staged(
        sinks: FakeSinks,
        dataJson: String,
        tenant: TenantId = TenantId.PLATFORM,
    ) = ScopePackStagedHandler(ScopePackReader(), sinks).apply(dataJson, tenant)

    private fun activated(
        sinks: FakeSinks,
        dataJson: String,
        tenant: TenantId = TenantId.PLATFORM,
    ) = ScopePackActivatedHandler(ScopePackReader(), sinks).apply(dataJson, tenant)

    @Test
    fun `INV-AC-25429 F1 a staged scope pack is stored with its coded pairs and a held version is ignored`() {
        val sinks = FakeSinks()
        staged(sinks, stagedData()) shouldBe HandleOutcome.APPLIED
        sinks.staged shouldBe
            listOf(
                StagedScopePack(
                    "gst-scope",
                    "2026.1.0",
                    setOf(ScopePackRow(Integer.parseInt("380"), UpstreamFunction.BILL_FOR_PAYMENT)),
                ),
            )
        sinks.fresh = false
        staged(sinks, stagedData()) shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-25429 F2 a staged pack of another type is ignored and reaches no sink`() {
        val sinks = FakeSinks()
        staged(sinks, stagedData(packType = "CALENDAR")) shouldBe HandleOutcome.IGNORED
        sinks.staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25429 F3 an activation is stored and a held activation is ignored`() {
        val sinks = FakeSinks()
        activated(sinks, activatedData) shouldBe HandleOutcome.APPLIED
        sinks.activations shouldBe listOf(ScopePackActivation("gst-scope", "2026.1.0", LocalDate.parse("2026-12-01")))
        sinks.fresh = false
        activated(sinks, activatedData) shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-25429 F4 a foreign tenant, a missing version and a code outside the contract are violations`() {
        val sinks = FakeSinks()
        val foreign = TenantId(UUID.randomUUID())
        assertThrows<ContractViolation> { staged(sinks, stagedData(), foreign) }
        assertThrows<ContractViolation> { activated(sinks, activatedData, foreign) }
        assertThrows<ContractViolation> { staged(sinks, stagedData(version = null)) }
        assertThrows<ContractViolation> { activated(sinks, activatedData.replace("\"version\":\"2026.1.0\",", "")) }
        assertThrows<ContractViolation> { staged(sinks, stagedData(code = "\"382\"")) }
        sinks.staged shouldBe emptyList()
        sinks.activations shouldBe emptyList()
    }

    private fun subscription(bean: JdbcInboxConsumer): ConsumerSubscription {
        val field = JdbcInboxConsumer::class.java.getDeclaredField("subscription")
        field.isAccessible = true
        return field.get(bean) as ConsumerSubscription
    }

    @Test
    fun `INV-AC-25429 F5 the context registers both consumers under the two names and topics`(
        @TempDir dir: Path,
    ) {
        CanonicalContext.offline(dir).run { context ->
            // The beans the application context builds, not the constants: a wrong name or topic wired in fails here.
            subscription(context.getBean("scopePackStagedConsumer", JdbcInboxConsumer::class.java)) shouldBe
                ConsumerSubscription("canonical-svc-rule-pack-staged", "gst.platform.rule-pack-staged.v1", "canonical")
            subscription(context.getBean("scopePackActivatedConsumer", JdbcInboxConsumer::class.java)) shouldBe
                ConsumerSubscription(
                    "canonical-svc-rule-pack-activated",
                    "gst.platform.rule-pack-activated.v1",
                    "canonical",
                )
            ScopePackFeed.STAGED_EVENT_TYPE shouldBe "com.dynokore.gst.platform.RulePackStaged.v1"
            ScopePackFeed.ACTIVATED_EVENT_TYPE shouldBe "com.dynokore.gst.platform.RulePackActivated.v1"
        }
    }

    private fun platformEnvelope(
        type: String,
        dataJson: String,
    ) = Envelope(
        id = EventId("ev-1"),
        source = "/gst/rules-svc",
        type = type,
        subject = "gst-scope",
        time = Instant.parse("2026-11-20T10:00:00Z"),
        tenant = TenantId.PLATFORM,
        entity = EntityRef.PLATFORM,
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = dataJson,
    )

    @Test
    fun `INV-AC-25429 F6 a real platform-tenant staged envelope is applied by the staged handler`() {
        val sinks = FakeSinks()
        val event = platformEnvelope(ScopePackFeed.STAGED_EVENT_TYPE, stagedData())
        ScopePackStagedHandler(ScopePackReader(), sinks).handle(event) shouldBe HandleOutcome.APPLIED
        sinks.staged.map { it.packIdentifier to it.version } shouldBe listOf("gst-scope" to "2026.1.0")
    }

    @Test
    fun `INV-AC-25429 F7 a real platform-tenant activated envelope is applied by the activated handler`() {
        val sinks = FakeSinks()
        val event = platformEnvelope(ScopePackFeed.ACTIVATED_EVENT_TYPE, activatedData)
        ScopePackActivatedHandler(ScopePackReader(), sinks).handle(event) shouldBe HandleOutcome.APPLIED
        sinks.activations shouldBe listOf(ScopePackActivation("gst-scope", "2026.1.0", LocalDate.parse("2026-12-01")))
    }

    @Test
    fun `INV-AC-25429 F8 the look-alike rules-namespace types cannot be built as platform envelopes`() {
        assertThrows<IllegalArgumentException> {
            platformEnvelope("com.dynokore.gst.rules.RulePackStaged.v1", stagedData())
        }
        assertThrows<IllegalArgumentException> {
            platformEnvelope("com.dynokore.gst.rules.RulePackActivated.v1", activatedData)
        }
    }
}
