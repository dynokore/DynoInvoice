package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The inbox. `submission-svc`, `reconciliation-svc` and `transport-svc` do not exist yet; the
 * fixtures in `Handlers.kt` play them. INV-AC-2149 (a duplicated `PayloadReady` makes one
 * downstream call) and INV-AC-2205 (killed after the state change, before commit: applied once).
 */
@Tag("integration")
class InboxConsumerIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a PayloadReady delivered twice produces one submission, one queue item and one transport call`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            val producer = harness.payload
            producer.inTx(ServiceKit.TENANT) {
                producer.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                    producer.docContext(ServiceKit.TENANT, doc),
                )
            }
            harness.drain(producer.relay)
            val envelope = harness.logOf(PAYLOAD_READY_TOPIC).single()
            // The relay redelivers the same event after a simulated crash: same id, a new log record.
            PostgresEventBinding(harness.eventLogForRelay).publish(PAYLOAD_READY_TOPIC, envelope)

            val kit = harness.submission
            val transport = TransportStub()
            val consumer =
                harness.consumer(
                    "submission-svc",
                    PAYLOAD_READY_TOPIC,
                    "submission",
                    kit.boundary,
                    SubmissionFixtureHandler(kit.boundary, transport),
                )
            val result = consumer.pollOnce()

            assertEquals(PollResult(applied = 1, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0), result)
            assertEquals(1, transport.calls.size)
            assertEquals(1, harness.count("select count(*) from submission.queue_item where doc_uuid = ?", doc))
            assertEquals(1, harness.count("select count(*) from submission.submission_row where doc_uuid = ?", doc))
            assertEquals(
                1,
                harness.count("select count(*) from submission.inbox where event_id = ?", envelope.id.value),
            )
            assertEquals(0, consumer.pollOnce().total) // the offset moved past both records
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a consumer killed after its state change and before commit applies the event once`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            acknowledge(harness, doc)

            val clock = MutableClock()
            val dying = KillableTransactionRunner(harness.fixture.appDataSource(), TenantContextImpl())
            val firstRun =
                harness.consumer(
                    "reconciliation-svc",
                    ACK_TOPIC,
                    "reconciliation",
                    dying,
                    ReconciliationFixtureHandler(dying, afterUpdate = { dying.armKill() }),
                    clock,
                )
            assertThrows(ProcessKilled::class.java) { firstRun.pollOnce() }
            assertEquals(0, harness.count("select count(*) from reconciliation.ack_read_model"))
            assertEquals(0, harness.count("select count(*) from reconciliation.inbox"))

            val restarted = harness.reconciliation
            val secondRun =
                harness.consumer(
                    "reconciliation-svc",
                    ACK_TOPIC,
                    "reconciliation",
                    restarted.boundary,
                    ReconciliationFixtureHandler(restarted.boundary),
                    clock,
                )
            clock.advance(java.time.Duration.ofMinutes(1)) // past the retry wait the failed run left
            assertEquals(1, secondRun.pollOnce().applied)
            assertEquals(
                1,
                harness.count("select ack_count from reconciliation.ack_read_model where doc_uuid = ?", doc),
            )
            assertEquals(1, harness.count("select count(*) from reconciliation.inbox"))
            assertEquals(0, secondRun.pollOnce().total)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a malformed record goes to dead letter and the consumer carries on`(image: String) {
        OutboxHarness(image).use { harness ->
            harness.fixture.runAsAdmin(
                "insert into eventlog.gst_payload_payload_ready_v1 (event_id, subject, envelope) " +
                    "values ('bad', 's', '{\"specversion\":\"1.0\"}')",
            )
            val kit = harness.submission
            val consumer =
                harness.consumer(
                    "submission-svc",
                    PAYLOAD_READY_TOPIC,
                    "submission",
                    kit.boundary,
                    SubmissionFixtureHandler(kit.boundary, TransportStub()),
                )

            assertEquals(1, consumer.pollOnce().deadLettered)
            assertEquals(1, harness.count("select count(*) from submission.event_dead_letter"))
            assertEquals(0, consumer.pollOnce().total)
        }
    }

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
        const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"
        const val ACK_TOPIC = "gst.submission.submission-acknowledged.v1"
    }
}
