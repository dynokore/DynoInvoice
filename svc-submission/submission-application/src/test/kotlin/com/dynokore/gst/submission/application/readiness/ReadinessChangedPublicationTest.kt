package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class ReadinessChangedPublicationTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000026"))

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class RecordingWriter : OutboxWriter {
        val events = mutableListOf<OutboundEvent>()
        val contexts = mutableListOf<EventContext>()

        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> {
            this.events += events
            contexts += context
            return emptyList()
        }
    }

    private val notReady =
        ReadinessSnapshot(
            CheckVerdict.NOT_READY,
            2,
            mapOf(LifecycleState.QUEUED to 1, LifecycleState.BLOCKED to 1),
        )
    private val ready = ReadinessSnapshot(CheckVerdict.READY, 0, mapOf(LifecycleState.ACKNOWLEDGED to 3))

    private fun facts(
        snapshot: ReadinessSnapshot,
        entity: String = "E-001",
    ) = ReadinessChangedFacts(entity, "2026Q1", snapshot, "chk-7")

    private fun stage(facts: ReadinessChangedFacts): RecordingWriter {
        val writer = RecordingWriter()
        OutboxReadinessSignals(writer, tenants).stage(facts)
        return writer
    }

    @Test
    fun `INV-AC-26601 a negative blocked count and a zero state count are refused`() {
        shouldThrow<IllegalArgumentException> { ReadinessSnapshot(CheckVerdict.READY, -1, emptyMap()) }
        shouldThrow<IllegalArgumentException> {
            ReadinessSnapshot(CheckVerdict.READY, 0, mapOf(LifecycleState.QUEUED to 0))
        }
    }

    @Test
    fun `INV-AC-26601 a blank entity, a blank period and a blank check id are refused`() {
        shouldThrow<IllegalArgumentException> { ReadinessChangedFacts(" ", "2026Q1", ready, "chk-7") }
        shouldThrow<IllegalArgumentException> { ReadinessChangedFacts("E-001", "", ready, "chk-7") }
        shouldThrow<IllegalArgumentException> { ReadinessChangedFacts("E-001", "2026Q1", ready, "\t") }
    }

    @Test
    fun `INV-AC-26601 a not ready snapshot stages one event with the whole expected data`() {
        val writer = stage(facts(notReady))
        writer.events.single().type shouldBe "com.dynokore.gst.submission.ReadinessChanged.v1"
        writer.events.single().dataJson shouldBe
            """{"entityRef":"E-001","period":"2026Q1","verdict":"NOT_READY",""" +
            """"countsByState":{"BLOCKED":1,"QUEUED":1},"blockedCount":2}"""
        val context = writer.contexts.single()
        context.aggregateKey shouldBe "E-001/2026Q1"
        context.docUuid shouldBe null
        context.tenant shouldBe tenant
        context.causation.value shouldBe "readiness-check:chk-7"
    }

    @Test
    fun `INV-AC-26601 a ready snapshot stages one event with blockedCount 0`() {
        val writer = stage(facts(ready))
        writer.events.single().type shouldBe ReadinessChangedPublication.EVENT_TYPE
        writer.events.single().dataJson shouldBe
            """{"entityRef":"E-001","period":"2026Q1","verdict":"READY",""" +
            """"countsByState":{"ACKNOWLEDGED":3},"blockedCount":0}"""
        writer.contexts.single().aggregateKey shouldBe "E-001/2026Q1"
        writer.contexts
            .single()
            .causation.value shouldBe "readiness-check:chk-7"
    }

    @Test
    fun `INV-AC-26601 a quote and a backslash in the entity reference are escaped`() {
        val entity = "E\"1\\x"
        val data = ReadinessChangedPublication.data(facts(ready, entity))
        data.substringBefore(",\"period\"") shouldBe "{\"entityRef\":\"E\\\"1\\\\x\""
    }
}
