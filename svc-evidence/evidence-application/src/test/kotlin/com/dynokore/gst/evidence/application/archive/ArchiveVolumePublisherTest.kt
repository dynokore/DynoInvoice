package com.dynokore.gst.evidence.application.archive

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ArchiveVolumePublisherTest {
    private val tenant = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789"))
    private val at = Instant.parse("2026-10-29T01:02:03.500Z")

    private class Calls {
        val order = mutableListOf<String>()
        val recorded = mutableListOf<List<Any>>()
        val events = mutableListOf<OutboundEvent>()
        val contexts = mutableListOf<EventContext>()
    }

    private class Ledger(
        private val calls: Calls,
    ) : ArchiveVolumeLedger {
        override fun last(
            tenant: TenantId,
            entityRef: String,
        ): Long? = null

        override fun record(
            tenant: TenantId,
            entityRef: String,
            bytes: Long,
            measuredAt: Instant,
        ) {
            calls.order += "record"
            calls.recorded += listOf(tenant, entityRef, bytes, measuredAt)
        }
    }

    private class Outbox(
        private val calls: Calls,
        private val failure: Throwable? = null,
    ) : OutboxWriter {
        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> {
            calls.order += "append"
            failure?.let { throw it }
            calls.events += events
            calls.contexts += context
            return emptyList()
        }
    }

    private class CountingClock(
        private val instants: List<Instant>,
    ) : Clock() {
        var reads = 0

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = instants[minOf(reads++, instants.size - 1)]
    }

    @Test
    fun `publish records the ledger row and appends one event with the same measuredAt`() {
        val calls = Calls()
        val clock = CountingClock(listOf(at, at.plusSeconds(60)))
        ArchiveVolumePublisher(Ledger(calls), Outbox(calls), clock).publish(tenant, "E-001", 1300L)

        clock.reads shouldBe 1
        calls.recorded.single() shouldBe listOf(tenant, "E-001", 1300L, at)
        val event = calls.events.single()
        event.type shouldBe "com.dynokore.gst.evidence.ArchiveVolumeMeasured.v1"
        event.dataJson shouldBe
            "{\"tenantRef\":\"abcdef01-2345-6789-abcd-ef0123456789\",\"entityRef\":\"E-001\"," +
            "\"bytes\":1300,\"measuredAt\":\"2026-10-29T01:02:03.500Z\"}"
        calls.contexts.single().tenant shouldBe tenant
        calls.contexts
            .single()
            .entity.value shouldBe "E-001"
    }

    @Test
    fun `an unchanged value still appends an event`() {
        val calls = Calls()
        val publisher = ArchiveVolumePublisher(Ledger(calls), Outbox(calls), Clock.fixed(at, ZoneOffset.UTC))
        publisher.publish(tenant, "E-001", 5L)
        publisher.publish(tenant, "E-001", 5L)
        calls.events.size shouldBe 2
    }

    @Test
    fun `a failed append propagates after the ledger call`() {
        val calls = Calls()
        val boom = IllegalStateException("outbox down")
        val publisher = ArchiveVolumePublisher(Ledger(calls), Outbox(calls, boom), Clock.fixed(at, ZoneOffset.UTC))
        shouldThrow<IllegalStateException> { publisher.publish(tenant, "E-001", 5L) } shouldBe boom
        calls.order shouldBe listOf("record", "append")
        calls.events.size shouldBe 0
    }

    @Test
    fun `a negative volume is refused before any write`() {
        val calls = Calls()
        val publisher = ArchiveVolumePublisher(Ledger(calls), Outbox(calls), Clock.fixed(at, ZoneOffset.UTC))
        shouldThrow<IllegalStateException> { publisher.publish(tenant, "E-001", -1L) }
        calls.order shouldBe emptyList()
    }

    @Test
    fun `an entity reference with quotes is escaped in the payload`() {
        val calls = Calls()
        ArchiveVolumePublisher(Ledger(calls), Outbox(calls), Clock.fixed(at, ZoneOffset.UTC))
            .publish(tenant, "E\"1\\", 0L)
        calls.events
            .single()
            .dataJson
            .contains("\"entityRef\":\"E\\\"1\\\\\"") shouldBe true
    }
}
