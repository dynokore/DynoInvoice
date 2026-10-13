package com.dynokore.platform.contracttests.consumer

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Clock
import java.time.LocalDate

/**
 * product-runtime's consumer pacts of tenancy-svc's OrganisationStructureChanged and EntityProfileChanged (events.yaml,
 * feeds_runtime_replicas; INV-REQ-1200), read by its [StructureFeedHandler] exactly as the structure feed reads a
 * record. Each message's payload validates against the pinned platform-contracts schema (CS-38). tenancy-svc
 * verifies them in its own build (INV-REQ-1225 b2; INV-Q-3925).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = Contracts.TENANCY_SVC, providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class TenancyEventsPactTest {
    private val handler = StructureFeedHandler(StructureReplica(Clock.systemUTC()))

    private fun read(message: V4Interaction.AsynchronousMessage): Any {
        val envelope = Contracts.envelopes.read(Contracts.text(message))
        return handler.decode(envelope)
    }

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun structureChanged(builder: MessagePactBuilder): V4Pact =
        builder
            .given("entity E-001 moved under group G-1")
            .expectsToReceive("an OrganisationStructureChanged carrying the full node")
            .withContent(
                Contracts.envelope(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, SOURCE, true, true) {
                    stringMatcher("nodeType", "^[A-Z_]+$", "ENTITY")
                    stringType("nodeRef", "E-001")
                    stringMatcher("changeType", "^[A-Z_]+$", "MOVED")
                    node(this)
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun profileChanged(builder: MessagePactBuilder): V4Pact =
        builder
            .given("entity E-001's GST registration number changed")
            .expectsToReceive("an EntityProfileChanged carrying the full node")
            .withContent(
                Contracts.envelope(ProductEventTypes.ENTITY_PROFILE_CHANGED, SOURCE, true, true) {
                    stringType("entityRef", "E-001")
                    minArrayLike("changedAttributes", 1)
                        .stringType("attribute", "gstRegistrationNumber")
                        .stringType("newValue", "M90312345A")
                        .closeObject()!!
                        .closeArray()
                    stringMatcher("effectiveDate", Contracts.DATE, "2026-01-01")
                    node(this)
                },
            ).toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "structureChanged")
    fun `the structure feed reads the moved node`(messages: List<V4Interaction.AsynchronousMessage>) {
        val message = messages.single()
        Contracts.assertPayloadConforms(SCHEMA_STRUCTURE, Contracts.text(message))
        val event = read(message).shouldBeInstanceOf<OrganisationStructureChanged>()
        event.nodeRef shouldBe "E-001"
        event.change shouldBe StructureChange.MOVED
        event.node.kind shouldBe StructureNodeKind.ENTITY
        event.node.parentRef shouldBe "G-1"
        event.node.structureVersion shouldBe 9
    }

    @Test
    @PactTestFor(pactMethod = "profileChanged")
    fun `the structure feed reads the profile change's node`(messages: List<V4Interaction.AsynchronousMessage>) {
        val message = messages.single()
        Contracts.assertPayloadConforms(SCHEMA_PROFILE, Contracts.text(message))
        val event = read(message).shouldBeInstanceOf<EntityProfileChanged>()
        event.entityRef shouldBe "E-001"
        event.node.profile
            .single()
            .effectiveDate shouldBe LocalDate.of(2026, 1, 1)
    }

    private companion object {
        const val SOURCE = "/gst/tenancy-svc"
        const val SCHEMA_STRUCTURE = "tenancy/schemas/organisation-structure-changed-payload.schema.json"
        const val SCHEMA_PROFILE = "tenancy/schemas/entity-profile-changed-payload.schema.json"

        /** The full-state node both events carry (INV-T-3298), with the fields the structure replica keeps. */
        fun node(payload: PactDslJsonBody) {
            payload
                .`object`("node")
                .stringMatcher("kind", "^[A-Z_]+$", "ENTITY")
                .stringType("parentRef", "G-1")
                .booleanType("retired", false)
                .stringType("name", "Acme Pte Ltd")
                .stringMatcher("code", "^[A-Z0-9]{1,10}$", "ACME")
                .minArrayLike("profile", 1)
                .stringType("attribute", "gstRegistrationNumber")
                .stringType("value", "M90312345A")
                .stringMatcher("effectiveDate", Contracts.DATE, "2026-01-01")
                .closeObject()!!
                .closeArray()!!
                .let { it as PactDslJsonBody }
                .integerType("structureVersion", 9L)
                .closeObject()
        }
    }
}
