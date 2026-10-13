package com.dynokore.platform.contracttests.consumer

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.serviceruntime.events.PlatformEventReader
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.PolicySource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate

/**
 * service-runtime's consumer pact of PolicyChanged (events.yaml: produced by every service owning a variation point,
 * feeds_runtime_replicas; INV-API-3306). The reading code is service-runtime's [PlatformEventReader], which
 * product-runtime's PolicyFeed hands each record to; the producer code is config-resolver's policy writer, so the
 * provider is config-resolver, which verifies this pact in the same pipeline (provider.PolicyChangedProviderTest;
 * INV-REQ-1225 b2, coordinator ruling INV-Q-3925). The payload validates against the pinned schema (CS-38).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = Contracts.CONFIG_RESOLVER,
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V4,
)
class PolicyChangedPactTest {
    @Pact(consumer = Contracts.SERVICE_RUNTIME)
    fun entityOverride(builder: MessagePactBuilder): V4Pact =
        builder
            .given(STATE)
            .expectsToReceive(DESCRIPTION)
            .withContent(
                Contracts.envelope(
                    PlatformEventTypes.POLICY_CHANGED,
                    source = null,
                    entity = true,
                    docSequence = false,
                ) {
                    stringMatcher("scope", "^ENTITY:.+$", "ENTITY:E-001")
                    stringMatcher("vpId", "^VP-\\d+$", "VP-12")
                    minArrayLike("value", 1)
                        .stringType("key", "B")
                        .closeObject()!!
                        .closeArray()
                    stringMatcher("effectiveDate", Contracts.DATE, "2026-10-01")
                    stringMatcher("source", "^(DEFAULT|PROFILE|OVERRIDE)$", "OVERRIDE")
                },
            ).toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "entityOverride")
    fun `the policy feed reads an entity override`(messages: List<V4Interaction.AsynchronousMessage>) {
        val text = Contracts.text(messages.single())
        Contracts.assertPayloadConforms(SCHEMA, text)
        val event = PlatformEventReader().policyChanged(PlatformEventTypes.POLICY_CHANGED_TOPIC, LogRecord(7, text))
        event.variationPointId shouldBe "VP-12"
        event.scope.entity shouldBe "E-001"
        event.effectiveDate shouldBe LocalDate.of(2026, 10, 1)
        event.source shouldBe PolicySource.OVERRIDE
        event.value shouldContain "\"key\":\"B\""
        event.offset shouldBe 7
    }

    companion object {
        const val STATE = "an entity-level override of VP-12 is set"
        const val DESCRIPTION = "a PolicyChanged for an entity override"
        const val SCHEMA = "service-runtime/schemas/policy-changed-payload.schema.json"
    }
}
