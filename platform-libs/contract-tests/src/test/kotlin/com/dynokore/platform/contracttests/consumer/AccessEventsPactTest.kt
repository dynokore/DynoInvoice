package com.dynokore.platform.contracttests.consumer

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Clock

/**
 * product-runtime's consumer pacts of access-svc's AuthorityChanged (events.yaml, feeds_runtime_replicas;
 * INV-REQ-1201, INV-Q-3259): the grant set and the agent consent, read by its [AuthorityFeedHandler] as the
 * authority feed reads a record. Each payload validates against the pinned schema (CS-38). access-svc verifies them
 * in its own build (INV-REQ-1225 b2; INV-Q-3925).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = Contracts.ACCESS_SVC, providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class AccessEventsPactTest {
    private val handler = AuthorityFeedHandler(AuthorityReplica(Clock.systemUTC()))

    private fun read(message: V4Interaction.AsynchronousMessage): AuthorityChanged {
        val envelope = Contracts.envelopes.read(Contracts.text(message))
        return handler.decode(envelope)
    }

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun grantSet(builder: MessagePactBuilder): V4Pact = builder
        .given("P-1 was granted approve on entity E-001")
        .expectsToReceive("an AuthorityChanged carrying the principal's grant set for an entity")
        .withContent(
            Contracts.envelope(ProductEventTypes.AUTHORITY_CHANGED, SOURCE, entity = true, docSequence = true) {
                stringMatcher("changeKind", "^GRANT$", "GRANT")
                stringType("principalRef", "P-1")
                integerType("authorityVersion", 4L)
                `object`("grantRecord")
                    .stringType("entityRef", "E-001")
                    .minArrayLike("grants", 1)
                    .stringType("action", "approve")
                    .stringMatcher("scopeLevel", "^[A-Z_]+$", "ENTITY")
                    .stringType("scopeRef", "E-001")
                    .stringMatcher("validFrom", INSTANT, "2026-01-01T00:00:00Z")
                    .closeObject()!!
                    .closeArray()!!
                    .closeObject()
            },
        ).toPact(V4Pact::class.java)

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun consent(builder: MessagePactBuilder): V4Pact = builder
        .given("agent P-9 holds a live consent for client entity C-7")
        .expectsToReceive("an AuthorityChanged carrying an agent consent's coverage")
        .withContent(
            Contracts.envelope(ProductEventTypes.AUTHORITY_CHANGED, SOURCE, entity = true, docSequence = true) {
                stringMatcher("changeKind", "^CONSENT$", "CONSENT")
                stringType("principalRef", "P-9")
                integerType("authorityVersion", 2L)
                `object`("consentRecord")
                    .stringType("clientEntityRef", "C-7")
                    .stringMatcher("state", "^[A-Z_]+$", "LIVE")
                    .eachLike("scopeItems", PactDslJsonRootValue.stringMatcher("^[A-Z_]+$", "SUBMIT_INVOICE_DATA"), 1)
                    .stringMatcher("validFrom", INSTANT, "2026-01-01T00:00:00Z")
                    .closeObject()
            },
        ).toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "grantSet")
    fun `the authority feed reads the grant set`(messages: List<V4Interaction.AsynchronousMessage>) {
        val message = messages.single()
        Contracts.assertPayloadConforms(SCHEMA, Contracts.text(message))
        val event = read(message)
        event.principalId shouldBe "P-1"
        event.authorityVersion shouldBe 4L
        val record = event.record.shouldBeInstanceOf<AuthorityRecord.GrantRecord>()
        record.entityRef shouldBe "E-001"
        record.grants.single().action shouldBe "approve"
    }

    @Test
    @PactTestFor(pactMethod = "consent")
    fun `the authority feed reads the consent coverage`(messages: List<V4Interaction.AsynchronousMessage>) {
        val message = messages.single()
        Contracts.assertPayloadConforms(SCHEMA, Contracts.text(message))
        val record = read(message).record.shouldBeInstanceOf<AuthorityRecord.ConsentRecord>()
        record.clientEntityRef shouldBe "C-7"
        record.state shouldBe ConsentState.LIVE
        record.scopeItems shouldBe setOf(ConsentScopeItem.SUBMIT_INVOICE_DATA)
    }

    private companion object {
        const val SOURCE = "/gst/access-svc"
        const val SCHEMA = "access/schemas/authority-changed-payload.schema.json"
        const val INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"
    }
}
