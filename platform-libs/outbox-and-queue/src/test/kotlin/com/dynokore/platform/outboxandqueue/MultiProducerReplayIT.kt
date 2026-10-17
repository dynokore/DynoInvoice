package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * A topic with several catalogue producers (owner ruling INV-Q-3788 A; INV-AC-3817, INV-TS-3818; Keel INV-T-3385) on
 * PostgreSQL 17 and 18: one request moves the head once and writes one ReplayRequested per producer under one replay
 * id; each producer answers only its own request on its pair's replay topic; the incident stays open while one
 * producer has not answered and closes once, when every producer's ReplayCompleted has been applied; a full sync
 * deletes every addressee's docsequence rows, so an addressee's other docsequence topic is rebuilt (INV-Q-3843).
 */
@Tag("integration")
class MultiProducerReplayIT {
    private val topic = "gst.platform.evidence-artefact-recorded.v1"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `one replay id addresses every producer and the incident closes only when all have completed`(image: String) {
        OutboxHarness(image).use { harness ->
            val evidence = harness.evidence
            val kits = producerKits(harness)
            val producers = kits.values.map { it.service }.sorted()
            assertEquals(
                producers,
                harness.catalogue
                    .topic(topic)!!
                    .producers
                    .sorted(),
                "every catalogue producer",
            )
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

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3843 a full sync deletes each addressee's docsequence rows, its other topics rebuilt`(image: String) {
        OutboxHarness(image).use { harness ->
            val doc = UUID.randomUUID()
            val tenant = ServiceKit.TENANT
            // evidence-svc applied payload-svc's PayloadReady for doc at docsequence 3, and something of canonical-svc.
            harness.fixture.runAsAdmin(
                "insert into evidence.consumer_sequence values " +
                    "('evidence-svc', '/gst/payload-svc', '$doc', '$tenant', 3), " +
                    "('evidence-svc', '/gst/canonical-svc', 's', '$tenant', 7)",
            )
            val kits = producerKits(harness)
            val producers = kits.values.map { it.service }.sorted()
            val replayId = UUID.randomUUID()
            fullSync(harness, replayId, producers)
            val rows = "select count(*) from evidence.consumer_sequence"
            assertEquals(1L, harness.count(rows), "every addressee's rows deleted")
            assertEquals(1L, harness.count("$rows where source = '/gst/canonical-svc'"), "another producer's row kept")

            // payload-svc replays doc at its current docsequence 3: applied, not STALE, and held again.
            harness.drain(harness.evidence.relay)
            val applied = mutableListOf<String>()
            answer(harness, kits.getValue("payload"), listOf(PayloadStates(doc)))
            val result = intake(harness, "payload", "payload-svc") { applied += it.type }.pollOnce()
            assertEquals(listOf(2, 0), listOf(result.applied, result.stale), "the state and the marker")
            assertEquals(listOf(TestCatalogue.PAYLOAD_READY), applied)
            assertEquals(3L, harness.count("select last_value from evidence.consumer_sequence where subject = '$doc'"))
        }
    }

    // evidence-svc's full sync addressed to every one of [producers], on its runtime login bound to the platform.
    private fun fullSync(
        harness: OutboxHarness,
        replayId: UUID,
        producers: List<String>,
    ) {
        val syncTenants = TenantContextImpl()
        val syncTx = TransactionRunnerImpl(harness.fixture.runtimeDataSource(), syncTenants)
        val clock = Clock.systemUTC()
        val catalogue = harness.catalogue
        val writer =
            JdbcOutboxWriter(
                JooqKit.module("evidence", syncTenants, syncTx),
                "evidence",
                "evidence-svc",
                "evidence-svc-w",
                catalogue,
                clock,
            )
        syncTenants.bindPlatform("svc:test/job:probe") {
            syncTx.inTransaction {
                JdbcConsumerReplay("evidence", syncTx, harness.reader, writer, clock).fullSync(
                    "evidence-svc",
                    listOf(topic, "gst.payload.payload-ready.v1"),
                    producers.map { ReplayRequest(replayId, it, ReplayMode.FULL_SYNC, null) },
                    emptyList(),
                    CorrelationId("c-$replayId"),
                    CausationId("o-$replayId"),
                )
            }
        }
    }

    /** The other catalogue producers of [topic] (access-svc, masterdata-svc, rules-svc) and payload-svc, by schema. */
    private fun producerKits(harness: OutboxHarness): Map<String, ServiceKit> {
        val others = listOf("access", "masterdata", "rules")
        return others.associateWith { schema ->
            harness.fixture.createService(schema)
            ServiceKit(harness, schema, "$schema-svc", harness.catalogue)
        } + ("payload" to harness.payload)
    }

    /** payload-svc's PayloadReady state source: one document at docsequence 3 in the fixture tenant. */
    private class PayloadStates(
        private val doc: UUID,
    ) : ReplayStateSource {
        override val eventType = TestCatalogue.PAYLOAD_READY

        override fun tenants() = listOf(TenantId(ServiceKit.TENANT))

        override fun page(
            scope: ReplayScope?,
            cursor: String?,
            limit: Int,
        ): ReplayPage {
            val state =
                ReplayedState(
                    eventType,
                    """{"version":3}""",
                    EntityRef("entity-$doc"),
                    doc.toString(),
                    3L,
                    DocUuid(doc),
                    LocalDate.of(2026, 9, 30),
                )
            return ReplayPage(if (cursor == null) listOf(state) else emptyList(), null)
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
        sources: List<ReplayStateSource> = emptyList(),
    ): List<ReplayAnswer> {
        val responder =
            JdbcReplayResponder(
                kit.service,
                kit.schema,
                harness.catalogue,
                harness.reader,
                harness.fixture.runtimeDataSource(),
                kit.scope,
                kit.writer,
                sources,
                harness.rebuildSink,
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
        seen: (Envelope) -> Unit = {},
    ): JdbcReplayIntake {
        val evidence = harness.evidence
        return JdbcReplayIntake(
            ConsumerSubscription("evidence-svc", "gst.$prefix.replay-evidence.v1", "evidence"),
            harness.catalogue,
            producer,
            harness.readerFor(harness.fixture.replayLogin("evidence-svc")),
            harness.fixture.runtimeDataSource(),
            evidence.scope,
            evidence.dsl,
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    seen(event)
                    return HandleOutcome.APPLIED
                }
            },
            harness.rebuildSink,
        )
    }
}
