package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A producing service answers one replay at a time across all its instances (Keel INV-T-9100; ruling INV-Q-9087 A;
 * INV-AC-9092; INV-REQ-1205 b4), on PostgreSQL 17 and 18: `canonical-svc` runs two responder instances over one
 * database and three consumers (reconciliation-svc, submission-svc, evidence-svc) each with a pending FULL_SYNC
 * request.
 * The replays in progress are counted from the first page of a state source to the ReplayCompleted marker's commit.
 */
@Tag("integration")
class ReplayAnswerLockIT {
    private val live = "gst.canonical.canonical-version-created.v1"
    private val clock = Clock.systemUTC()
    private val consumers = listOf("evidence", "reconciliation", "submission")

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `two instances and three pending full syncs never run two replays at once`(image: String) {
        OutboxHarness(image).use { harness ->
            val source = CountingStates(harness)
            val a = responder(harness, harness.canonical, "canonical-svc", source)
            val b = responder(harness, harness.canonical, "canonical-svc", source)
            consumers.forEach { fullSync(harness, it, listOf("canonical-svc")) }

            // A is inside its first replay (first page read, no marker yet): B finds the lock taken and does nothing.
            val pool = Executors.newSingleThreadExecutor()
            try {
                val gate = CountDownLatch(1)
                source.gate = gate
                val running = pool.submit<List<ReplayAnswer>> { a.runOnce() }
                assertTrue(source.entered.await(TIMEOUT, TimeUnit.SECONDS), "A reached its first page")
                assertEquals(emptyList<ReplayAnswer>(), b.runOnce(), "B is refused while A answers")
                assertEquals(0L, harness.count(MARKERS), "no marker yet")
                assertEquals(0L, harness.count("select count(*) from canonical.inbox"), "B took no inbox row")
                gate.countDown()
                assertEquals(3, running.get(TIMEOUT, TimeUnit.SECONDS).size, "A then answers the three requests")
            } finally {
                pool.shutdownNow()
            }
            repeat(MAX_RUNS) {
                a.runOnce()
                b.runOnce()
            }
            assertEquals(3L, harness.count(MARKERS), "three markers, one per request")
            assertEquals(1, source.maxInProgress.get(), "never more than one replay in progress")
            harness.drain(harness.canonical.relay)
            consumers.forEach {
                val topic = "eventlog.gst_canonical_replay_${it}_v1"
                val type = TestCatalogue.CANONICAL_VERSION_CREATED
                assertEquals(2L, harness.count("select count(*) from $topic where envelope->>'type' = '$type'"), it)
                assertEquals(
                    1L,
                    harness.count("select count(*) from $topic where envelope->>'type' = '$COMPLETED'"),
                    it,
                )
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the three markers are written within three runs of each instance, alternating`(image: String) {
        OutboxHarness(image).use { harness ->
            val source = CountingStates(harness)
            val a = responder(harness, harness.canonical, "canonical-svc", source)
            val b = responder(harness, harness.canonical, "canonical-svc", source)
            consumers.forEach { fullSync(harness, it, listOf("canonical-svc")) }
            val replayed = mutableListOf<ReplayAnswer>()
            repeat(MAX_RUNS) {
                replayed += a.runOnce()
                replayed += b.runOnce()
            }
            assertEquals(consumers.sorted(), replayed.map { it.consumer.removeSuffix("-svc") }.sorted())
            assertEquals(3L, harness.count(MARKERS))
            assertEquals(1, source.maxInProgress.get())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a request for another producer is acknowledged without the lock, an own request waits for it`(image: String) {
        OutboxHarness(image).use { harness ->
            val canonical = responder(harness, harness.canonical, "canonical-svc", CountingStates(harness))
            fullSync(harness, "evidence", listOf("payload-svc")) // addressed to payload-svc alone
            holdingAnswerLock(harness.canonical, "canonical-svc") {
                assertEquals(emptyList<ReplayAnswer>(), canonical.runOnce())
                val inbox = "select count(*) from canonical.inbox where consumer = 'canonical-svc'"
                assertEquals(1L, harness.count(inbox), "acknowledged though the lock is held elsewhere")
                val position = "select next_offset from canonical.consumer_offset where topic = '$EVIDENCE_REQUESTS'"
                assertEquals(2L, harness.count(position), "the position moved past it")

                fullSync(harness, "submission", listOf("canonical-svc"))
                assertEquals(emptyList<ReplayAnswer>(), canonical.runOnce(), "refused while the lock is held")
                assertEquals(1L, harness.count(inbox), "no inbox row for the refused request")
                assertEquals(0L, harness.count(MARKERS))
            }
            assertEquals(
                listOf("submission-svc"),
                canonical.runOnce().map { it.consumer },
                "read again by the next run",
            )
            assertEquals(1L, harness.count(MARKERS))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a second service answers under its own key while the first service's lock is held`(image: String) {
        OutboxHarness(image).use { harness ->
            val payload = responder(harness, harness.payload, "payload-svc", CountingStates(harness, "payload"))
            fullSync(harness, "evidence", listOf("payload-svc"))
            holdingAnswerLock(harness.canonical, "canonical-svc") {
                assertEquals(1, payload.runOnce().size, "payload-svc is not blocked by canonical-svc's lock")
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `two requests of one consumer run in log order, and the topic after the last served goes first`(image: String) {
        OutboxHarness(image).use { harness ->
            val canonical = responder(harness, harness.canonical, "canonical-svc", CountingStates(harness))
            val first = fullSync(harness, "reconciliation", listOf("canonical-svc"))
            // the consumer sends its next request once the first is complete on its side (one pending request per pair)
            harness.fixture.runAsAdmin(
                "update reconciliation.consumer_replay set completed_at = now(), closed_at = now()",
            )
            val second = fullSync(harness, "reconciliation", listOf("canonical-svc"))
            assertEquals(listOf(first, second), canonical.runOnce().map { it.replayId }, "log order")

            // reconciliation (the second topic) was served last: the next run starts at submission, then wraps
            harness.fixture.runAsAdmin(
                "update reconciliation.consumer_replay set completed_at = now(), closed_at = now()",
            )
            consumers.forEach { fullSync(harness, it, listOf("canonical-svc")) }
            assertEquals(
                listOf("submission-svc", "evidence-svc", "reconciliation-svc"),
                canonical.runOnce().map { it.consumer },
            )
        }
    }

    private fun responder(
        harness: OutboxHarness,
        kit: ServiceKit,
        service: String,
        source: ReplayStateSource,
    ) = JdbcReplayResponder(
        service,
        kit.schema,
        harness.catalogue,
        harness.reader,
        kit.scope,
        kit.dsl,
        kit.writer,
        listOf(source),
        harness.rebuildSink,
        clock,
        pageSize = 1,
    )

    /** The consumer's full sync to every producer of [producers] under one replay id, on its runtime login. */
    private fun fullSync(
        harness: OutboxHarness,
        prefix: String,
        producers: List<String>,
    ): UUID {
        val replayId = UUID.randomUUID()
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(harness.fixture.runtimeDataSource(), tenants, null, SigningFixture.keys())
        val dsl = JooqKit.module(prefix, tenants, tx)
        val writer = JdbcOutboxWriter(dsl, prefix, "$prefix-svc", "$prefix-svc-w", harness.catalogue, clock)
        tenants.bindPlatform("svc:test/job:probe") {
            tx.inTransaction {
                JdbcConsumerReplay(prefix, dsl, harness.reader, writer, clock).fullSync(
                    "$prefix-svc",
                    listOf(live),
                    producers.map { ReplayRequest(replayId, it, ReplayMode.FULL_SYNC, null) },
                    emptyList(),
                    CorrelationId("c-$replayId"),
                    CausationId("o-$replayId"),
                )
            }
        }
        harness.drain(
            when (prefix) {
                "evidence" -> harness.evidence.relay
                "submission" -> harness.submission.relay
                else -> harness.reconciliation.relay
            },
        )
        return replayId
    }

    /** Runs [block] while another transaction (its own thread and connection) holds [service]'s answer lock. */
    private fun holdingAnswerLock(
        kit: ServiceKit,
        service: String,
        block: () -> Unit,
    ) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val holder =
                pool.submit {
                    kit.scope.bindWorkload(TenantId.PLATFORM) {
                        kit.scope.transactions.inTransaction {
                            val key = DSL.inline("$service.replay-answer")
                            val hash = DSL.function(DSL.name("pg_catalog", "hashtext"), SQLDataType.INTEGER, key)
                            val lock =
                                DSL.function(
                                    DSL.name("pg_catalog", "pg_try_advisory_xact_lock"),
                                    SQLDataType.BOOLEAN,
                                    hash,
                                )
                            check(
                                kit.dsl
                                    .select(lock)
                                    .fetchSingle()
                                    .get(lock) == true,
                            ) { "the test could not take the lock" }
                            held.countDown()
                            release.await(TIMEOUT, TimeUnit.SECONDS)
                        }
                    }
                }
            assertTrue(held.await(TIMEOUT, TimeUnit.SECONDS), "the lock is held")
            try {
                block()
            } finally {
                release.countDown()
            }
            holder.get(TIMEOUT, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Two states in the fixture tenant. The replays in progress are the first pages read less the markers written;
     * [gate] holds the first page of the next replay until the test opens it.
     */
    private class CountingStates(
        private val harness: OutboxHarness,
        private val schema: String = "canonical",
    ) : ReplayStateSource {
        override val eventType =
            if (schema ==
                "canonical"
            ) {
                TestCatalogue.CANONICAL_VERSION_CREATED
            } else {
                TestCatalogue.PAYLOAD_READY
            }
        val maxInProgress = AtomicInteger(0)
        val entered = CountDownLatch(1)

        @Volatile var gate: CountDownLatch? = null
        private val started = AtomicInteger(0)

        override fun tenants() = listOf(TenantId(ServiceKit.TENANT))

        override fun page(
            scope: ReplayScope?,
            cursor: String?,
            limit: Int,
        ): ReplayPage {
            if (cursor == null) {
                val inProgress =
                    started.incrementAndGet() -
                        harness.count("select count(*) from $schema.outbox where type = '$COMPLETED'").toInt()
                maxInProgress.accumulateAndGet(inProgress, ::maxOf)
                gate?.let {
                    gate = null
                    entered.countDown()
                    it.await(TIMEOUT, TimeUnit.SECONDS)
                }
            }
            val position = cursor?.toInt() ?: 0
            val doc = UUID.nameUUIDFromBytes("doc-$position".toByteArray())
            val state =
                ReplayedState(
                    eventType,
                    """{"version":${position + 1}}""",
                    EntityRef("entity-$doc"),
                    doc.toString(),
                    position + 1L,
                    DocUuid(doc),
                    LocalDate.of(2026, 9, 30),
                )
            return ReplayPage(listOf(state), if (position + 1 < 2) (position + 1).toString() else null)
        }
    }

    private companion object {
        const val TIMEOUT = 30L
        const val MAX_RUNS = 3
        const val COMPLETED = "com.dynokore.gst.platform.ReplayCompleted.v1"
        const val MARKERS = "select count(*) from canonical.outbox where type = '$COMPLETED'"
        const val EVIDENCE_REQUESTS = "gst.evidence.replay-requested.v1"
    }
}
