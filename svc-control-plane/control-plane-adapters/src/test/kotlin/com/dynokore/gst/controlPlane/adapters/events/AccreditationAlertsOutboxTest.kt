package com.dynokore.gst.controlPlane.adapters.events

import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlert
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.accreditation.TaskReason
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

class AccreditationAlertsOutboxTest {
    private val written = ArrayList<Pair<List<OutboundEvent>, EventContext>>()
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                written += events to context
                return emptyList()
            }
        }

    @Test
    fun `INV-AC-2340 the alert is one platform event under the platform tenant and entity`() {
        AccreditationAlertsOutbox(outbox).raise(
            AccreditationAlert(
                "ALERT-1",
                "TASK-1",
                TaskReason.ACCREDITATION_LAPSED,
                Instant.parse("2026-10-19T08:00:00Z"),
                "2026.10.1",
                LocalDate.parse("2026-10-18"),
            ),
            RequestContext("corr", "cause"),
        )
        val (events, context) = written.single()
        events.single().type shouldBe "com.dynokore.gst.platform.AccreditationAlertRaised.v1"
        context.tenant shouldBe TenantId.PLATFORM
        context.entity shouldBe EntityRef.PLATFORM
        val data = JsonMapper.builder().build().readTree(events.single().dataJson)
        listOf(
            "alertRef" to "ALERT-1",
            "taskRef" to "TASK-1",
            "reason" to "ACCREDITATION_LAPSED",
            "raisedAt" to "2026-10-19T08:00:00Z",
            "releaseRef" to "2026.10.1",
            "currentUntil" to "2026-10-18",
        ).forEach { (field, value) -> data.get(field).asString() shouldBe value }
    }

    @Test
    fun `the envelope refuses the old control-plane type under the platform tenant, not the platform one`() {
        fun envelope(type: String) =
            Envelope(
                EventId("018f0000-0000-7000-8000-000000000001"),
                "/gst/control-plane",
                type,
                "TASK-1",
                Instant.parse("2026-10-19T08:00:00Z"),
                TenantId.PLATFORM,
                EntityRef.PLATFORM,
                null,
                null,
                1,
                "i-1",
                CorrelationId("c"),
                CausationId("k"),
                "{}",
            )
        envelope("com.dynokore.gst.platform.AccreditationAlertRaised.v1").type shouldBe
            "com.dynokore.gst.platform.AccreditationAlertRaised.v1"
        shouldThrow<IllegalArgumentException> { envelope("com.dynokore.gst.control-plane.AccreditationAlertRaised.v1") }
    }
}
