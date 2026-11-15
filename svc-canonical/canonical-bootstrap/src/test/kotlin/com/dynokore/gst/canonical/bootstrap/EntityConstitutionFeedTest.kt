package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.EntityConstitutionReader
import com.dynokore.gst.canonical.application.entity.EntityConstitutionFact
import com.dynokore.gst.canonical.application.entity.EntityConstitutionStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/** INV-AC-21402: the EntityProfileChanged consumer over a fake [EntityConstitutionStore]. */
class EntityConstitutionFeedTest {
    private val t1 = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    private class FakeStore : EntityConstitutionStore {
        val facts = mutableListOf<EntityConstitutionFact>()

        override fun apply(fact: EntityConstitutionFact) {
            facts += fact
        }
    }

    private fun data(
        constitution: String? = "\"PRIVATE_LIMITED\"",
        node: String? = "{\"kind\":\"ENTITY\",\"structureVersion\":9}",
    ): String =
        buildString {
            append("{\"entityRef\":\"ENT-1\",\"changedAttributes\":[],\"effectiveDate\":\"2026-07-01\"")
            if (constitution != null) append(",\"constitution\":$constitution")
            if (node != null) append(",\"node\":$node")
            append("}")
        }

    private fun envelope(dataJson: String) =
        Envelope(
            id = EventId("evt-1"),
            source = "/gst/tenancy-svc",
            type = EntityConstitutionFeed.EVENT_TYPE,
            subject = "ENT-1",
            time = Instant.parse("2026-07-01T00:00:00Z"),
            tenant = t1,
            entity = EntityRef("ENT-1"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "i-1",
            correlation = CorrelationId("c-1"),
            causation = CausationId("k-1"),
            dataJson = dataJson,
        )

    private fun handle(dataJson: String): Pair<FakeStore, Result<HandleOutcome>> {
        val store = FakeStore()
        val handler = EntityConstitutionHandler(EntityConstitutionReader(), store)
        return store to runCatching { handler.handle(envelope(dataJson)) }
    }

    private fun refused(dataJson: String): Pair<FakeStore, ContractViolation> {
        val (store, result) = handle(dataJson)
        return store to (result.exceptionOrNull() as? ContractViolation ?: error("not refused: $result"))
    }

    @Test
    fun `INV-AC-21402 E1 valid event applies exactly one fact`() {
        val (store, result) = handle(data())
        result.getOrThrow() shouldBe HandleOutcome.APPLIED
        store.facts shouldBe listOf(EntityConstitutionFact(t1.value, "ENT-1", "PRIVATE_LIMITED", 9))
    }

    @Test
    fun `INV-AC-21402 E2 event without constitution is acknowledged and applies nothing`() {
        val (store, result) = handle(data(constitution = null))
        result.getOrThrow() shouldBe HandleOutcome.IGNORED
        store.facts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21402 E3 numeric constitution is a contract violation`() {
        val (store, _) = refused(data(constitution = "7"))
        store.facts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21402 E4 empty constitution is a contract violation`() {
        val (store, _) = refused(data(constitution = "\"\""))
        store.facts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21402 E5 event without node is a contract violation naming node structureVersion`() {
        val (store, violation) = refused(data(node = null))
        store.facts shouldBe emptyList()
        violation.message!! shouldContain "node.structureVersion"
    }

    @Test
    fun `INV-AC-21402 E6 structureVersion 0 is a contract violation`() {
        val (store, _) = refused(data(node = "{\"structureVersion\":0}"))
        store.facts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21402 E7 envelope without tenantid is a contract violation`() {
        // An Envelope cannot carry a domain event without a tenant (FR-K.3); the platform tenant is how it models
        // a missing tenantid, so the reader is called with it.
        val store = FakeStore()
        assertThrows<ContractViolation> { EntityConstitutionReader().read(data(), TenantId.PLATFORM) }
        store.facts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21402 E1 tenant comes from the envelope not from the data`() {
        val other = "22222222-2222-2222-2222-222222222222"
        val spoofed = data().replace("\"entityRef\"", "\"tenantId\":\"$other\",\"entityRef\"")
        val (store, _) = handle(spoofed)
        store.facts.single().tenantId shouldBe t1.value
    }

    @Test
    fun `INV-AC-21402 subscription lists exactly the topic and the event type`() {
        EntityConstitutionFeed.TOPIC shouldBe "gst.tenancy.entity-profile-changed.v1"
        EntityConstitutionFeed.EVENT_TYPE shouldBe "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
    }
}
