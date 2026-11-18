package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * INV-AC-24592: the inbox consumer hands the log record (offset and envelope JSON) to a record-aware
 * [EventHandler]; handlers of the old form and the replay intake keep working.
 */
@Tag("integration")
class RecordAwareEventHandlerIT {
    private val topic = "gst.payload.payload-ready.v1"
    private val json = EnvelopeJson()

    private class Recording : EventHandler {
        val pairs = CopyOnWriteArrayList<LogRecord>()
        val events = CopyOnWriteArrayList<Envelope>()

        override fun handle(event: Envelope): HandleOutcome = error("the record-aware method is the one called")

        override fun handle(
            event: Envelope,
            record: LogRecord,
        ): HandleOutcome {
            events += event
            pairs += record
            return HandleOutcome.APPLIED
        }
    }

    private class OldForm : EventHandler {
        val events = CopyOnWriteArrayList<Envelope>()

        override fun handle(event: Envelope): HandleOutcome {
            events += event
            return HandleOutcome.APPLIED
        }
    }

    /** Appends one event, relays it and publishes [count] - 1 copies with fresh ids; returns the topic's log. */
    private fun publish(
        harness: OutboxHarness,
        count: Int,
    ): List<LogRecord> {
        val producer = harness.payload
        producer.inTx(ServiceKit.TENANT) {
            producer.writer.append(
                listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                producer.docContext(ServiceKit.TENANT, UUID.randomUUID()),
            )
        }
        harness.drain(producer.relay)
        val first = harness.logOf(topic).single()
        repeat(count - 1) {
            val doc = UUID.randomUUID()
            harness.binding().publish(
                topic,
                first.copy(
                    id = EventId(UuidV7().next(Instant.now()).toString()),
                    subject = doc.toString(),
                    docUuid = DocUuid(doc),
                ),
            )
        }
        return harness.reader.read(topic, 0, LOG_LIMIT)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-24592 a record-aware handler gets offset and stored JSON of each polled record in order`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val log = publish(harness, 3)
            assertEquals(3, log.size)
            val handler = Recording()
            val kit = harness.submission
            val consumer = harness.consumer("submission-svc", topic, "submission", kit.boundary, handler)

            assertEquals(3, consumer.pollOnce().applied)

            assertEquals(log.map { it.offset to it.envelopeJson }, handler.pairs.map { it.offset to it.envelopeJson })
            assertEquals(log.map { json.read(it.envelopeJson) }, handler.events.toList())
            assertTrue(handler.pairs.none { it.replayed })
            assertEquals(log.map { it.offset }.sorted(), handler.pairs.map { it.offset })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-24592 an old-form handler is called as before and handle(event, record) equals handle(event)`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val log = publish(harness, 2)
            val handler = OldForm()
            val kit = harness.submission
            val consumer = harness.consumer("submission-svc", topic, "submission", kit.boundary, handler)

            val expected = PollResult(applied = 2, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
            assertEquals(expected, consumer.pollOnce())
            assertEquals(log.map { json.read(it.envelopeJson) }, handler.events.toList())
            val any = handler.events.first()
            assertEquals(handler.handle(any), handler.handle(any, LogRecord(7, "{}")))
            val ignoring =
                object : EventHandler {
                    override fun handle(event: Envelope) = HandleOutcome.IGNORED
                }
            assertEquals(HandleOutcome.IGNORED, ignoring.handle(any, LogRecord(7, "{}")))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-24592 a replayed dead letter gives REPLAY_OFFSET and the stored text, a polled one does not`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val log = publish(harness, 1)
            val stored = log.single()
            val envelope = json.read(stored.envelopeJson)
            val handler = Recording()
            val kit = harness.submission
            val consumer = harness.consumer("submission-svc", topic, "submission", kit.boundary, handler)

            // the stored text differs from the re-serialised one (leading whitespace), so equality proves it is passed
            val raw = "\n" + json.write(envelope)
            assertTrue(raw != json.write(envelope))
            assertEquals(ReplayOutcome.APPLIED, consumer.applyReplayed(raw, TenantId(ServiceKit.TENANT)))
            val replayed = handler.pairs.single()
            assertEquals(LogRecord.REPLAY_OFFSET, replayed.offset)
            assertEquals(raw, replayed.envelopeJson)
            assertTrue(replayed.replayed)

            // the same event now polled is a duplicate: no second call
            consumer.pollOnce()
            assertEquals(1, handler.pairs.size)
            assertFalse(LogRecord(stored.offset, stored.envelopeJson).replayed)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-24592 a dead letter replayed by the queue gives the handler the stored text byte for byte`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val stored = publish(harness, 1).single()
            // the same envelope in a text that differs from EnvelopeJson.write (surrounding whitespace)
            val raw = "\n  " + stored.envelopeJson + "\n"
            assertTrue(raw != json.write(json.read(stored.envelopeJson)))
            val kit = harness.submission
            val letterId =
                JooqKit.recordDeadLetter(
                    harness.fixture,
                    "submission",
                    LetterToRecord("submission-svc", topic, 1, ServiceKit.TENANT, "handler schema mismatch", raw),
                )
            val letters = JdbcDeadLetterTriage(kit.scope, kit.dsl, "submission", MutableClock())
            assertEquals(1, letters.replay(DeadLetterScope(consumer = "submission-svc"), "submission-svc").queued)
            val readBack = checkNotNull(checkNotNull(letters.get(letterId)).raw)
            assertEquals(raw, readBack)

            val handler = Recording()
            val inbox = harness.consumer("submission-svc", topic, "submission", kit.boundary, handler)
            val limiter = LaneRateLimiter(kit.scope, kit.dsl, "submission", LaneLimits())
            val queue = JdbcWorkQueue(kit.scope, kit.dsl, "submission", limiter, MutableClock(), "submission-svc")
            assertEquals(ReplayOutcome.APPLIED, JdbcReplayDelivery(queue, kit.scope, inbox).runOnce())

            val replayed = handler.pairs.single()
            assertEquals(readBack, replayed.envelopeJson)
            assertEquals(LogRecord.REPLAY_OFFSET, replayed.offset)
            assertTrue(replayed.replayed)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-24592 a record-aware handler inside the replay intake gets the record, a marker reaches no call`(
        image: String,
    ) {
        ProducerReplayIT().Scenario(image).use { s ->
            val live = "gst.canonical.canonical-version-created.v1"
            val tenant = TenantId(UUID.randomUUID())
            val markerHandler = Recording()
            val liveHandler = Recording()
            val markerIntake =
                intake(
                    s,
                    ConsumerSubscription(
                        "reconciliation-svc",
                        "gst.canonical.replay-reconciliation.v1",
                        "reconciliation",
                    ),
                    s.harness.readerFor(s.harness.fixture.replayLogin("reconciliation-svc")),
                    markerHandler,
                )
            val liveIntake =
                intake(
                    s,
                    ConsumerSubscription("record-aware-svc", live, "reconciliation"),
                    s.harness.reader,
                    liveHandler,
                )
            s.change(tenant, UUID.randomUUID(), 1)
            s.publishCanonical()
            val log = s.harness.reader.read(live, 0, 100)
            liveIntake.pollOnce()
            answerFullSync(s)

            // the marker is read and recorded; no handler call is made for it
            assertEquals(1, markerIntake.pollOnce().applied)
            assertTrue(markerHandler.pairs.isEmpty())
            assertEquals(listOf("reconciliation-svc" to live), s.harness.closed.map { it.consumer to it.topic })

            // a record on a live topic reaches the handler inside the intake with its offset and stored text
            assertTrue(log.isNotEmpty())
            assertTrue(liveHandler.pairs.isNotEmpty())
            assertTrue(liveHandler.pairs.none { it.replayed })
            assertEquals(liveHandler.events.size, liveHandler.pairs.size)
            val got = liveHandler.pairs.map { it.offset to it.envelopeJson }
            assertEquals(log.map { it.offset to it.envelopeJson }, got)
        }
    }

    private fun answerFullSync(s: ProducerReplayIT.Scenario) {
        s.purgeLive()
        assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
        s.fullSync()
        s.publishReconciliation()
        s.responder().runOnce()
        s.publishCanonical()
    }

    private fun intake(
        s: ProducerReplayIT.Scenario,
        subscription: ConsumerSubscription,
        reader: EventLogReader,
        handler: EventHandler,
    ) = JdbcReplayIntake(
        subscription,
        s.harness.catalogue,
        "canonical-svc",
        reader,
        s.harness.reconciliation.scope,
        s.harness.reconciliation.dsl,
        handler,
        s.harness.rebuildSink,
        Clock.systemUTC(),
    )

    private companion object {
        const val LOG_LIMIT = 100
    }
}
