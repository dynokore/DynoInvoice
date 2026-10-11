package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.util.UUID

/**
 * A topic with several catalogue producers (owner ruling INV-Q-3788 A; INV-AC-3817, INV-TS-3818; Keel INV-T-3385) on
 * PostgreSQL 17 and 18: one request moves the head once and writes one ReplayRequested per producer under one replay
 * id; each producer answers only its own request on its pair's replay topic; the incident stays open while one
 * producer has not answered and closes once, when every producer's ReplayCompleted has been applied.
 */
@Tag("integration")
class MultiProducerReplayIT {
    private val topic = "gst.platform.evidence-artefact-recorded.v1"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `one replay id addresses every producer and the incident closes only when all have completed`(image: String) {
        OutboxHarness(image).use { harness ->
            val evidence = harness.evidence
            val kits =
                listOf("access", "masterdata", "rules").associateWith { schema ->
                    harness.fixture.createService(schema)
                    ServiceKit(harness, schema, "$schema-svc", harness.catalogue)
                } + ("payload" to harness.payload)
            val producers = kits.values.map { it.service }.sorted()
            assertEquals(producers, harness.catalogue.topic(topic)!!.producers.sorted(), "every catalogue producer")
            val replayId = UUID.randomUUID()
            val moved = request(harness, replayId, producers)
            assertEquals(listOf(topic), moved.map { it.topic }, "the head moved once")
            assertEquals(1L, harness.count("select count(*) from evidence.consumer_offset"))
            harness.drain(evidence.relay)
            val requests = harness.logOf("gst.evidence.replay-requested.v1")
            assertEquals(producers, requests.map { ReplayJson().readRequest(it.dataJson).producer }.sorted())
            assertEquals(setOf(replayId), requests.map { ReplayJson().readRequest(it.dataJson).replayId }.toSet())

            val intakes = kits.mapValues { (schema, kit) -> intake(harness, schema, kit.service) }
            // rules-svc is stopped: the other three answer, each only its own request.
            for (schema in listOf("access", "masterdata", "payload")) {
                assertEquals(listOf(0L), answer(harness, kits.getValue(schema)).map { it.eventsReplayed }, schema)
                assertEquals(1, intakes.getValue(schema).pollOnce().applied, "$schema's marker")
            }
            intakes.values.forEach { it.pollOnce() }
            assertTrue(harness.closed.isEmpty(), "open while rules-svc has not answered")
            val completed = "select count(*) from evidence.consumer_replay where completed_at is not null"
            assertEquals(3L, harness.count(completed))

            answer(harness, kits.getValue("rules"))
            assertEquals(1, intakes.getValue("rules").pollOnce().applied)
            intakes.values.forEach { it.pollOnce() }
            assertEquals(listOf(replayId to topic), harness.closed.map { it.replayId to it.topic }, "closed once")
        }
    }

    /** evidence-svc's one request to every one of [producers] under [replayId]: one head move. */
    private fun request(
        harness: OutboxHarness,
        replayId: UUID,
        producers: List<String>,
    ): List<MovedPosition> {
        val evidence = harness.evidence
        val scope = ReplayScope.Tenant(TenantId(ServiceKit.TENANT))
        val clock = Clock.systemUTC()
        val replay = JdbcConsumerReplay("evidence", evidence.boundary, harness.reader, evidence.writer, clock)
        val requests = producers.map { ReplayRequest(replayId, it, ReplayMode.SCOPE, scope) }
        val correlation = CorrelationId("c-$replayId")
        return evidence.inPlatformTx {
            replay.request("evidence-svc", listOf(topic), requests, correlation, CausationId("o-$replayId"))
        }
    }

    /** [kit]'s responder answers its own request with no state: the marker alone, on its pair's replay topic. */
    private fun answer(
        harness: OutboxHarness,
        kit: ServiceKit,
    ): List<ReplayAnswer> {
        val responder = JdbcReplayResponder(
            kit.service, kit.schema, harness.catalogue, harness.reader, harness.fixture.workerDataSource(),
            TenantScope(kit.tenants, kit.boundary, kit.boundary), kit.writer, emptyList(), harness.rebuildSink,
        )
        val answers = responder.runOnce()
        harness.drain(kit.relay)
        return answers
    }

    /** evidence-svc's intake of the ([producer], evidence-svc) replay topic, on its own replay-topic roles. */
    private fun intake(
        harness: OutboxHarness,
        prefix: String,
        producer: String,
    ): JdbcReplayIntake {
        val roles = harness.fixture.replayRoles("evidence-svc")
        val evidence = harness.evidence
        return JdbcReplayIntake(
            ConsumerSubscription("evidence-svc", "gst.$prefix.replay-evidence.v1", "evidence"),
            harness.catalogue,
            producer,
            PostgresEventLogReader(
                harness.fixture.roleDataSource(roles.claimRole),
                harness.fixture.roleDataSource(roles.readerRole),
            ),
            harness.fixture.workerDataSource(),
            TenantScope(evidence.tenants, evidence.boundary, evidence.boundary),
            object : EventHandler {
                override fun handle(event: Envelope) = HandleOutcome.APPLIED
            },
            harness.rebuildSink,
        )
    }
}
