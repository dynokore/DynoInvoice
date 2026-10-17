package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.UUID

/** Redelivery with bounded, jittered backoff, then the dead letter (ARC 4.2 step 5, INV-Q-2966). */
@Tag("integration")
class ConsumerRetryIT {
    private val policy =
        ConsumerRetryPolicy(
            maxAttempts = 3,
            initialDelay = Duration.ofSeconds(10),
            multiplier = 2.0,
            maxDelay = Duration.ofMinutes(1),
            jitter = 0.5,
        )

    /** Fails while [failFor] holds for the event's subject; otherwise acts as the reconciliation fixture. */
    private class FlakyHandler(
        private val inner: EventHandler,
        private val failFor: (String) -> Throwable?,
    ) : EventHandler {
        var calls = 0

        override fun handle(event: Envelope): HandleOutcome {
            calls++
            failFor(event.subject)?.let { throw it }
            return inner.handle(event)
        }
    }

    private fun consumerOf(
        harness: OutboxHarness,
        clock: MutableClock,
        retry: ConsumerRetryPolicy = policy,
        classifier: FailureClassifier = FailureClassifier.DEFAULT,
        failFor: (String) -> Throwable?,
    ): Pair<JdbcInboxConsumer, FlakyHandler> {
        val kit = harness.reconciliation
        val handler = FlakyHandler(ReconciliationFixtureHandler(kit.boundary), failFor)
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription("reconciliation-svc", ACK_TOPIC, "reconciliation", retry, classifier),
                harness.catalogue,
                harness.reader,
                kit.scope,
                kit.dsl,
                handler,
                harness.rebuildSink,
                clock,
                { 0.0 }, // no shortening: the waits are the full 10 s, 20 s
            )
        return consumer to handler
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a failing handler is redelivered after growing waits and a later success is applied once`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            acknowledge(harness, doc)
            val clock = MutableClock()
            var failures = 2
            val (consumer, handler) =
                consumerOf(harness, clock) { if (failures-- > 0) IllegalStateException("db down") else null }

            assertThrows(IllegalStateException::class.java) { consumer.pollOnce() }
            assertEquals(1, handler.calls)
            clock.advance(Duration.ofSeconds(9))
            assertEquals(0, consumer.pollOnce().total, "inside the first wait nothing is read")
            assertEquals(1, handler.calls)

            clock.advance(Duration.ofSeconds(1)) // 10 s: the first wait is over
            assertThrows(IllegalStateException::class.java) { consumer.pollOnce() }
            assertEquals(2, handler.calls)
            clock.advance(Duration.ofSeconds(19))
            assertEquals(0, consumer.pollOnce().total, "the second wait is 20 s, longer than the first")

            clock.advance(Duration.ofSeconds(1))
            assertEquals(1, consumer.pollOnce().applied)
            val ackCount = "select ack_count from reconciliation.ack_read_model where doc_uuid = ?"
            assertEquals(1, harness.count(ackCount, doc))
            assertEquals(1, harness.count("select count(*) from reconciliation.inbox"))
            assertEquals(0, harness.count("select count(*) from reconciliation.event_dead_letter"))
            assertEquals(0, consumer.pollOnce().total)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a handler that always fails is dead-lettered after the bound and the next record is consumed`(image: String) {
        OutboxHarness(image).use { harness ->
            val bad = UUID.randomUUID()
            val good = UUID.randomUUID()
            acknowledge(harness, bad)
            acknowledge(harness, good)
            val clock = MutableClock()
            val (consumer, handler) =
                consumerOf(harness, clock) { if (it == bad.toString()) IllegalStateException("stale") else null }

            assertThrows(IllegalStateException::class.java) { consumer.pollOnce() }
            assertEquals(
                0,
                harness.count("select count(*) from reconciliation.ack_read_model"),
                "the later record waits behind the failing one",
            )
            clock.advance(Duration.ofSeconds(10))
            assertThrows(IllegalStateException::class.java) { consumer.pollOnce() }
            clock.advance(Duration.ofSeconds(20))

            val last = consumer.pollOnce() // the third attempt: the bound
            assertEquals(1, last.deadLettered)
            assertEquals(1, last.applied, "the consumer moved on to the next record")
            assertEquals(3, handler.calls - 1, "three attempts for the failing record, then one call for the good one")
            assertEquals(1, harness.count("select count(*) from reconciliation.event_dead_letter"))
            assertEquals(
                1,
                harness.count(
                    "select count(*) from reconciliation.event_dead_letter where reason = " +
                        "'handler failed, retries exhausted after 3 attempts: java.lang.IllegalStateException'",
                ),
            )
            val acks = "select count(*) from reconciliation.ack_read_model where doc_uuid = ?"
            assertEquals(0, harness.count(acks, bad))
            assertEquals(1, harness.count(acks, good))
            assertEquals(0, consumer.pollOnce().total, "nothing is retried for ever")
            clock.advance(Duration.ofDays(1))
            assertEquals(0, consumer.pollOnce().total)
            assertEquals(3, handler.calls - 1)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a contract violation raised by the handler goes straight to dead letter`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            acknowledge(harness, doc)
            val (consumer, handler) = consumerOf(harness, MutableClock()) { ContractViolation("payload shape") }

            val result = consumer.pollOnce()

            assertEquals(1, result.deadLettered)
            assertEquals(1, handler.calls)
            assertEquals(
                1,
                harness.count("select count(*) from reconciliation.event_dead_letter where reason = 'payload shape'"),
            )
            assertTrue(consumer.pollOnce().total == 0)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an Error from the handler backs off and is dead-lettered like an exception`(image: String) {
        OutboxHarness(image).use { harness ->
            acknowledge(harness, UUID.randomUUID())
            val clock = MutableClock()
            val (consumer, handler) = consumerOf(harness, clock) { NotImplementedError("todo") }

            assertThrows(NotImplementedError::class.java) { consumer.pollOnce() }
            assertEquals(0, consumer.pollOnce().total, "inside the wait")
            clock.advance(Duration.ofSeconds(10))
            assertThrows(NotImplementedError::class.java) { consumer.pollOnce() }
            clock.advance(Duration.ofSeconds(20))
            assertEquals(1, consumer.pollOnce().deadLettered)
            assertEquals(3, handler.calls)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the attempt count survives a restart mid-retry and the bound still ends in the dead letter`(image: String) {
        OutboxHarness(image).use { harness ->
            acknowledge(harness, UUID.randomUUID())
            val clock = MutableClock()
            val always: (String) -> Throwable? = { IllegalStateException("bad record") }

            assertThrows(IllegalStateException::class.java) { restart(harness, clock, always).pollOnce() }
            clock.advance(Duration.ofSeconds(10))
            // A new instance (restart, or a second replica) carries on from the stored count and wait.
            assertThrows(IllegalStateException::class.java) { restart(harness, clock, always).pollOnce() }
            clock.advance(Duration.ofSeconds(20))
            val (third, handler) = consumerOf(harness, clock, failFor = always)
            assertEquals(1, third.pollOnce().deadLettered)
            assertEquals(1, handler.calls)
            assertEquals(1, harness.count("select count(*) from reconciliation.event_dead_letter"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a handler that kills the process is dead-lettered at the bound without running again`(image: String) {
        OutboxHarness(image).use { harness ->
            acknowledge(harness, UUID.randomUUID())
            val clock = MutableClock()
            val kill: (String) -> Throwable? = { OutOfMemoryError("simulated kill") }
            // Each run records its attempt, then dies: no catch block of the consumer runs.
            repeat(policy.maxAttempts) {
                assertThrows(OutOfMemoryError::class.java) { restart(harness, clock, kill).pollOnce() }
            }
            val (restarted, handler) = consumerOf(harness, clock, failFor = kill)
            assertEquals(1, restarted.pollOnce().deadLettered)
            assertEquals(0, handler.calls, "the handler is not called again")
            assertEquals(
                1,
                harness.count(
                    "select count(*) from reconciliation.event_dead_letter " +
                        "where reason like 'handler failed, retries exhausted after 3 attempts%'",
                ),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `infrastructure failures back off without using the bound until the give-up time`(image: String) {
        OutboxHarness(image).use { harness ->
            acknowledge(harness, UUID.randomUUID())
            val clock = MutableClock()
            val retry = policy.copy(maxDelay = Duration.ofSeconds(40), infrastructureGiveUp = Duration.ofHours(24))
            val lost: (String) -> Throwable? = { java.sql.SQLException("connection lost", "08006") }
            val (consumer, handler) = consumerOf(harness, clock, retry, failFor = lost)

            // More failures than maxAttempts (3): waits 10, 20, 40, 40, 40 s.
            for (wait in listOf(10L, 20L, 40L, 40L, 40L)) {
                assertThrows(java.sql.SQLException::class.java) { consumer.pollOnce() }
                clock.advance(Duration.ofSeconds(wait - 1))
                assertEquals(0, consumer.pollOnce().total, "still inside the $wait s wait")
                clock.advance(Duration.ofSeconds(1))
            }
            assertEquals(5, handler.calls)
            assertEquals(0, harness.count("select count(*) from reconciliation.event_dead_letter"))

            clock.advance(Duration.ofHours(24))
            assertEquals(1, consumer.pollOnce().deadLettered)
            assertEquals(
                1,
                harness.count(
                    "select count(*) from reconciliation.event_dead_letter " +
                        "where reason like 'handler failed on infrastructure failures for PT24H%'",
                ),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a consumer's own classifier decides what counts toward the bound`(image: String) {
        OutboxHarness(image).use { harness ->
            acknowledge(harness, UUID.randomUUID())
            val clock = MutableClock()
            val allInfra = FailureClassifier { FailureKind.INFRASTRUCTURE }
            val (consumer, handler) =
                consumerOf(harness, clock, classifier = allInfra) { IllegalStateException("remote said no") }
            repeat(policy.maxAttempts + 2) {
                assertThrows(IllegalStateException::class.java) { consumer.pollOnce() }
                clock.advance(Duration.ofMinutes(1))
            }
            assertEquals(policy.maxAttempts + 2, handler.calls)
            assertEquals(0, harness.count("select count(*) from reconciliation.event_dead_letter"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a StackOverflowError is a record failure that backs off and is dead-lettered at the bound`(image: String) {
        OutboxHarness(image).use { harness ->
            acknowledge(harness, UUID.randomUUID())
            val clock = MutableClock()
            val (consumer, handler) = consumerOf(harness, clock) { StackOverflowError() }

            assertThrows(StackOverflowError::class.java) { consumer.pollOnce() }
            assertEquals(0, consumer.pollOnce().total, "inside the wait")
            clock.advance(Duration.ofSeconds(10))
            assertThrows(StackOverflowError::class.java) { consumer.pollOnce() }
            clock.advance(Duration.ofSeconds(20))
            assertEquals(1, consumer.pollOnce().deadLettered)
            assertEquals(3, handler.calls)
        }
    }

    private fun restart(
        harness: OutboxHarness,
        clock: MutableClock,
        failFor: (String) -> Throwable?,
    ) = consumerOf(harness, clock, failFor = failFor).first

    private fun acknowledge(
        harness: OutboxHarness,
        doc: UUID,
    ) {
        val producer = harness.submission
        producer.inTx(ServiceKit.TENANT) {
            producer.writer.append(
                listOf(OutboundEvent(TestCatalogue.SUBMISSION_ACKNOWLEDGED, """{"ack":"a-1"}""")),
                producer.docContext(ServiceKit.TENANT, doc),
            )
        }
        harness.drain(producer.relay)
    }

    private companion object {
        const val ACK_TOPIC = "gst.submission.submission-acknowledged.v1"
    }
}
