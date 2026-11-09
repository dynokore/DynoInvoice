package com.dynokore.gst.masterdata.adapters.rate

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ScopePackHandlersTest {
    private val mapper = JsonMapper.builder().build()

    private class Memory : ScopePackStore {
        val versions = LinkedHashMap<Pair<String, String>, List<ApprovedSource>>()
        val activations = LinkedHashMap<Pair<String, String>, LocalDate>()

        override fun stage(
            packIdentifier: String,
            version: String,
            sources: List<ApprovedSource>,
            recordedAt: Instant,
        ) = versions.putIfAbsent(packIdentifier to version, sources) == null

        override fun activate(
            packIdentifier: String,
            version: String,
            effectiveFrom: LocalDate,
            recordedAt: Instant,
        ): Boolean {
            val latest = activations.filterKeys { it.first == packIdentifier }.values.maxOrNull()
            if (latest != null && !effectiveFrom.isAfter(latest)) return false
            return activations.putIfAbsent(packIdentifier to version, effectiveFrom) == null
        }

        override fun approvedOn(on: LocalDate): List<ApprovedSource>? = null
    }

    private fun envelope(
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/rules",
        type = type,
        subject = "scope",
        time = Instant.now(),
        tenant =
            com.dynokore.platform.tenancykernel
                .TenantId(UUID.randomUUID()),
        entity = EntityRef("ENT-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("c-1"),
        causation = CausationId("c-1"),
        dataJson = data,
    )

    private fun staged(
        packType: String,
        version: String = "1.0.0",
    ) = envelope(
        ConsumedRatePackEvents.RULE_PACK_STAGED,
        """{"packIdentifier":"sg-scope","packType":"$packType","version":"$version",""" +
            """"artefact":{"manifest":{},"checksums":{},"signature":"s",""" +
            """"contents":{"exchangeRateSources":[{"sourceId":"MAS","bases":["MID","BUY"]}]}}}""",
    )

    @Test
    fun `a SCOPE pack keeps its approved sources and a replay leaves one row`() {
        val store = Memory()
        val handler = RulePackStagedHandler(store, mapper)
        assertThat(handler.handle(staged("SCOPE"))).isEqualTo(HandleOutcome.APPLIED)
        assertThat(handler.handle(staged("SCOPE"))).isEqualTo(HandleOutcome.IGNORED)
        assertThat(store.versions).hasSize(1)
        assertThat(store.versions.values.single()).containsExactly(ApprovedSource("MAS", setOf("MID", "BUY")))
    }

    @Test
    fun `a pack type that is not exactly SCOPE stores nothing`() {
        val store = Memory()
        val handler = RulePackStagedHandler(store, mapper)
        listOf("scope", "Scope", "SCOPE ", "SCOPES", "X-SCOPE", "CODE_CATALOGUE").forEach {
            assertThat(handler.handle(staged(it))).describedAs(it).isEqualTo(HandleOutcome.IGNORED)
        }
        assertThat(store.versions).isEmpty()
    }

    @Test
    fun `a staged event without a pack identifier is a contract violation`() {
        val bad = envelope(ConsumedRatePackEvents.RULE_PACK_STAGED, """{"packType":"SCOPE","version":"1"}""")
        assertThatThrownBy {
            RulePackStagedHandler(
                Memory(),
                mapper,
            ).handle(bad)
        }.isInstanceOf(ContractViolation::class.java)
    }

    @Test
    fun `an activation not above the stored date is ignored and a later one is applied`() {
        val store = Memory()
        val handler = RulePackActivatedHandler(store, mapper)

        fun activated(
            version: String,
            from: String,
        ) = envelope(
            ConsumedRatePackEvents.RULE_PACK_ACTIVATED,
            """{"packIdentifier":"sg-scope","version":"$version","effectiveFrom":"$from"}""",
        )
        assertThat(handler.handle(activated("1.0.0", "2026-01-01"))).isEqualTo(HandleOutcome.APPLIED)
        assertThat(handler.handle(activated("1.0.0", "2026-01-01"))).isEqualTo(HandleOutcome.IGNORED)
        assertThat(handler.handle(activated("0.9.0", "2025-12-01"))).isEqualTo(HandleOutcome.IGNORED)
        assertThat(handler.handle(activated("2.0.0", "2026-06-01"))).isEqualTo(HandleOutcome.APPLIED)
        assertThat(store.activations).hasSize(2)
    }

    @Test
    fun `an activation with a malformed date is a contract violation`() {
        val bad =
            envelope(
                ConsumedRatePackEvents.RULE_PACK_ACTIVATED,
                """{"packIdentifier":"sg-scope","version":"1","effectiveFrom":"01/01/2026"}""",
            )
        assertThatThrownBy {
            RulePackActivatedHandler(Memory(), mapper).handle(bad)
        }.isInstanceOf(ContractViolation::class.java)
    }
}
