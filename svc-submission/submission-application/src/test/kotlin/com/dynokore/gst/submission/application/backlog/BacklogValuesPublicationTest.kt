package com.dynokore.gst.submission.application.backlog

import com.dynokore.gst.submission.domain.metrics.BacklogValues
import com.dynokore.gst.submission.domain.metrics.BacklogVersion
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BacklogValuesPublicationTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val today = LocalDate.parse("2026-11-12")

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

    private fun v(
        doc: String,
        state: LifecycleState,
        queuedAt: String = "2026-11-12T09:00:00Z",
        entity: String = "E-001",
        period: String = "2026Q1",
    ) = BacklogVersion(doc, 1, entity, period, state, Instant.parse(queuedAt), LocalDate.parse("2026-11-30"))

    private fun publish(vararg versions: BacklogVersion): RecordingWriter =
        RecordingWriter().also { BacklogValuesPublication.publish(versions.toList(), now, today, tenant, it) }

    @Test
    fun `INV-AC-20581 one event per entity and period with exactly the members of the contract`() {
        val w =
            publish(
                v("d1", LifecycleState.QUEUED),
                v("d2", LifecycleState.DEAD_LETTERED, "2026-11-12T08:00:00Z"),
                v("d3", LifecycleState.SENT_UNCONFIRMED),
                v("d4", LifecycleState.QUEUED, entity = "E-002"),
            )
        w.events.size shouldBe 2
        w.events[0].dataJson shouldBe
            "{\"entityRef\":\"E-001\",\"periodRef\":\"2026Q1\",\"queueDepth\":1,\"oldestUnsentAgeSeconds\":7200," +
            "\"failureRate\":0.5,\"unacknowledgedCount\":1,\"measuredAt\":\"2026-11-12T10:00:00Z\"}"
        w.contexts[0].aggregateKey shouldBe "E-001/2026Q1"
        w.contexts[1].aggregateKey shouldBe "E-002/2026Q1"
        w.contexts[0].causation.value shouldBe "backlog-evaluation:2026-11-12T10:00:00Z"
    }

    @Test
    fun `INV-AC-20581 oldestUnsentAgeSeconds is absent when nothing is unsent`() {
        val json = publish(v("d1", LifecycleState.SENT)).events.single().dataJson
        json.contains("oldestUnsentAgeSeconds") shouldBe false
        json.contains("\"queueDepth\":0") shouldBe true
    }

    @Test
    fun `INV-AC-20581 failureRate is 0 when no attempt completed`() {
        publish(v("d1", LifecycleState.QUEUED))
            .events
            .single()
            .dataJson
            .contains("\"failureRate\":0,") shouldBe true
    }

    @Test
    fun `INV-AC-20581 measuredAt equals the instant given`() {
        publish(v("d1", LifecycleState.QUEUED))
            .events
            .single()
            .dataJson
            .contains("\"measuredAt\":\"$now\"}") shouldBe
            true
    }

    @Test
    fun `INV-AC-20581 the event type equals the contract name`() {
        BacklogValuesPublication.EVENT_TYPE shouldBe "com.dynokore.gst.submission.BacklogValuesMeasured.v1"
        (BacklogValuesPublication.EVENT_TYPE == "com.dynokore.gst.submission.BacklogValueMeasured.v1") shouldBe false
        publish(v("d1", LifecycleState.QUEUED)).events.single().type shouldBe
            "com.dynokore.gst.submission.BacklogValuesMeasured.v1"
    }

    @Test
    fun `INV-AC-20581 no version writes no event and no log line carries a count`() {
        publish().events shouldBe emptyList()
        val source =
            java.io
                .File(
                    "src/main/kotlin/com/dynokore/gst/submission/application/backlog/BacklogValuesPublication.kt",
                ).readText()
        Regex("(?i)\\blog(ger)?\\b|println|slf4j").containsMatchIn(source) shouldBe false
    }

    private fun rateOf(
        numerator: Int,
        denominator: Int,
    ): String =
        BacklogValuesPublication
            .data(BacklogValues("E", "P", 0, Duration.ZERO, Ratio(numerator, denominator), 0, emptyList()), now)
            .substringAfter("\"failureRate\":")
            .substringBefore(",")

    @Test
    fun `INV-AC-20581 failureRate is the decimal at 6 places rounded HALF_EVEN with trailing zeros stripped`() {
        rateOf(0, 0) shouldBe "0"
        rateOf(1, 3) shouldBe "0.333333"
        rateOf(2, 3) shouldBe "0.666667"
        rateOf(1, 2) shouldBe "0.5"
        rateOf(1, 1) shouldBe "1"
        rateOf(1, 128) shouldBe "0.007812"
        rateOf(3, 128) shouldBe "0.023438"
    }
}
