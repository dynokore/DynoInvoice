package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The [ReplayCompletionListener] of [JdbcReplayIntake] (INV-T-9055, INV-Q-9047 A): told once per completed replay with
 * the replay id, the consumer and the topics whose incident was closed. It runs on [ProducerReplayIT]'s scenario.
 */
@Tag("integration")
class ReplayCompletionListenerIT {
    private val live = "gst.canonical.canonical-version-created.v1"
    private val consumer = "reconciliation-svc"
    private val tenant = TenantId(UUID.randomUUID())
    private val clock = Clock.systemUTC()

    private class Told(
        val replayId: UUID,
        val consumer: String,
        val topics: List<String>,
    ) {
        override fun equals(other: Any?) =
            other is Told && replayId == other.replayId && consumer == other.consumer && topics == other.topics

        override fun hashCode() = replayId.hashCode()

        override fun toString() = "Told($replayId, $consumer, $topics)"
    }

    private class Recorder : ReplayCompletionListener {
        val told = CopyOnWriteArrayList<Told>()

        override fun onReplayCompleted(
            replayId: UUID,
            consumer: String,
            topics: List<String>,
        ) {
            told += Told(replayId, consumer, topics)
        }
    }

    private fun handler() =
        object : EventHandler {
            override fun handle(event: Envelope) = HandleOutcome.APPLIED
        }

    private fun intake(
        s: ProducerReplayIT.Scenario,
        listener: ReplayCompletionListener?,
    ) = JdbcReplayIntake(
        ConsumerSubscription(consumer, "gst.canonical.replay-reconciliation.v1", "reconciliation"),
        s.harness.catalogue,
        "canonical-svc",
        s.harness.readerFor(s.harness.fixture.replayLogin(consumer)),
        s.harness.reconciliation.scope,
        s.harness.reconciliation.dsl,
        handler(),
        s.harness.rebuildSink,
        clock,
        listener,
    )

    /** The consumer falls behind the watermark and asks canonical-svc for a full sync; the answer is published. */
    private fun fullSyncAnswered(s: ProducerReplayIT.Scenario): UUID {
        s.change(tenant, UUID.randomUUID(), 1)
        s.publishCanonical()
        s.purgeLive()
        assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
        val replayId = s.fullSync()
        s.publishReconciliation()
        s.responder().runOnce()
        s.publishCanonical()
        return replayId
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a full sync from one producer calls the listener once with the replay, the consumer and the closed topics`(
        image: String,
    ) {
        ProducerReplayIT().Scenario(image).use { s ->
            val listener = Recorder()
            val intake = intake(s, listener)
            val replayId = fullSyncAnswered(s)
            intake.pollOnce()
            assertEquals(listOf(Told(replayId, consumer, listOf(live))), listener.told.toList())
            assertEquals(listOf(consumer to live), s.harness.closed.map { it.consumer to it.topic })
            intake.pollOnce()
            assertEquals(1, listener.told.size, "once per replay")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a marker of another replay id on the same topic does not call the listener`(image: String) {
        ProducerReplayIT().Scenario(image).use { s ->
            val listener = Recorder()
            val intake = intake(s, listener)
            s.change(tenant, UUID.randomUUID(), 1)
            s.publishCanonical()
            val canonical = s.harness.canonical
            val stranger = UUID.randomUUID()
            canonical.inPlatformTx {
                canonical.writer.appendCompleted(
                    "gst.canonical.replay-reconciliation.v1",
                    stranger,
                    0,
                    CorrelationId("test-${UUID.randomUUID()}"),
                    CausationId("op-${UUID.randomUUID()}"),
                )
            }
            s.publishCanonical()
            assertEquals(1, intake.pollOnce().total, "the marker is read")
            assertTrue(listener.told.isEmpty(), "no pending replay of that id")
            assertTrue(s.harness.closed.isEmpty())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `another consumer's replay marker does not call this listener`(image: String) {
        ProducerReplayIT().Scenario(image).use { s ->
            val mine = Recorder()
            val theirs = Recorder()
            val intake = intake(s, mine)
            val sub = s.harness.submission
            val submissionIntake =
                JdbcReplayIntake(
                    ConsumerSubscription("submission-svc", "gst.canonical.replay-submission.v1", "submission"),
                    s.harness.catalogue,
                    "canonical-svc",
                    s.harness.readerFor(s.harness.fixture.replayLogin("submission-svc")),
                    sub.scope,
                    sub.dsl,
                    handler(),
                    s.harness.rebuildSink,
                    clock,
                    theirs,
                )
            s.change(tenant, UUID.randomUUID(), 1)
            s.publishCanonical()
            s.purgeLive()
            val request = submissionRequest(s)
            s.harness.drain(sub.relay)
            s.responder().runOnce()
            s.publishCanonical()
            assertEquals(0, intake.pollOnce().total)
            submissionIntake.pollOnce()
            assertTrue(mine.told.isEmpty())
            assertEquals(listOf(Told(request.replayId, "submission-svc", listOf(live))), theirs.told.toList())
        }
    }

    private fun submissionRequest(s: ProducerReplayIT.Scenario): ReplayRequest {
        val sub = s.harness.submission
        val request =
            ReplayRequest(UUID.randomUUID(), "canonical-svc", ReplayMode.SCOPE, ReplayScope.Tenant(tenant))
        sub.inPlatformTx {
            JdbcConsumerReplay("submission", sub.dsl, s.harness.reader, sub.writer, clock)
                .request(
                    "submission-svc",
                    listOf(live),
                    request,
                    CorrelationId("test-${UUID.randomUUID()}"),
                    CausationId("op-${UUID.randomUUID()}"),
                )
        }
        return request
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a listener that throws does not undo the completion and is not called again`(image: String) {
        ProducerReplayIT().Scenario(image).use { s ->
            val calls = CopyOnWriteArrayList<UUID>()
            val intake =
                intake(s) { replayId, _, _ ->
                    calls += replayId
                    error("listener failure")
                }
            val replayId = fullSyncAnswered(s)
            intake.pollOnce()
            assertEquals(listOf(replayId), calls.toList())
            assertEquals(listOf(replayId), s.harness.closed.map { it.replayId }, "the completion stands")
            intake.pollOnce()
            assertEquals(1, calls.size, "not called again")
            assertEquals(1, s.harness.closed.size)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the intake built without a listener completes as before`(image: String) {
        ProducerReplayIT().Scenario(image).use { s ->
            val replayId = fullSyncAnswered(s)
            s.intake.pollOnce()
            assertEquals(listOf(replayId), s.harness.closed.map { it.replayId })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an 'every service' replay calls the listener after the last source's marker`(image: String) {
        OutboxHarness(image).use { harness ->
            val audit = "gst.platform.audit-entry-recorded.v1"
            val evidence = harness.evidence
            val live = evidenceLive(harness, audit)
            for (kit in listOf(harness.payload, harness.submission)) {
                kit.inTx(ServiceKit.TENANT) {
                    val event = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, "{}")
                    kit.writer.append(listOf(event), kit.docContext(ServiceKit.TENANT, UUID.randomUUID()))
                }
                harness.drain(kit.relay)
            }
            assertEquals(2, live.pollOnce().applied)
            val replayId = UUID.randomUUID()
            requestEveryService(harness, replayId)
            harness.drain(evidence.relay)
            val listener = Recorder()
            val intake =
                JdbcReplayIntake(
                    ConsumerSubscription("evidence-svc", "gst.platform.replay-evidence.v1", "evidence"),
                    harness.catalogue,
                    CatalogueEntry.EVERY_SERVICE,
                    harness.readerFor(harness.fixture.replayLogin("evidence-svc")),
                    evidence.scope,
                    evidence.dsl,
                    handler(),
                    harness.rebuildSink,
                    clock,
                    listener,
                )
            marker(harness, harness.payload, "payload")
            assertEquals(1, intake.pollOnce().applied)
            assertTrue(listener.told.isEmpty(), "submission-svc has not answered yet")
            marker(harness, harness.submission, "submission")
            assertEquals(1, intake.pollOnce().applied)
            assertEquals(listOf(Told(replayId, "evidence-svc", listOf(audit))), listener.told.toList())
        }
    }

    private fun requestEveryService(
        harness: OutboxHarness,
        replayId: UUID,
    ) {
        val evidence = harness.evidence
        val request =
            ReplayRequest(replayId, CatalogueEntry.EVERY_SERVICE, ReplayMode.SCOPE, ReplayScope.Tenant(tenant))
        evidence.inPlatformTx {
            JdbcConsumerReplay("evidence", evidence.dsl, harness.reader, evidence.writer, clock)
                .request(
                    "evidence-svc",
                    listOf("gst.platform.audit-entry-recorded.v1"),
                    request,
                    CorrelationId("test-${UUID.randomUUID()}"),
                    CausationId("op-${UUID.randomUUID()}"),
                )
        }
    }

    private fun evidenceLive(
        harness: OutboxHarness,
        topic: String,
    ) = JdbcInboxConsumer(
        ConsumerSubscription("evidence-svc", topic, "evidence", trackSources = true),
        harness.catalogue,
        harness.reader,
        harness.evidence.scope,
        harness.evidence.dsl,
        handler(),
        harness.rebuildSink,
    )

    /** [kit]'s responder answers the pending 'every service' request with no state: the marker alone. */
    private fun marker(
        harness: OutboxHarness,
        kit: ServiceKit,
        schema: String,
    ) {
        val responder =
            JdbcReplayResponder(
                kit.service,
                schema,
                harness.catalogue,
                harness.reader,
                kit.scope,
                kit.dsl,
                kit.writer,
                emptyList(),
                harness.rebuildSink,
            )
        assertEquals(listOf(0L), responder.runOnce().map { it.eventsReplayed })
        harness.drain(kit.relay)
    }
}
