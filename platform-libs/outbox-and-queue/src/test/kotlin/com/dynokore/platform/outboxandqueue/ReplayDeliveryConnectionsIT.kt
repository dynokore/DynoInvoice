package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * A DataSource wrapper that records, per thread, the peak of connections open at once. With [permits] it also plays
 * a bounded pool: a thread that finds none free waits [waitSeconds] and then fails, as a lane's connection timeout
 * does. [failOn] makes a statement whose SQL it accepts fail (a fault injected at the connection level).
 */
class CountingDataSource(
    private val delegate: DataSource,
    permits: Int? = null,
    private val waitSeconds: Long = 3,
) : DataSource by delegate {
    private class Counts {
        var open = 0
        var peak = 0
    }

    private val perThread = ThreadLocal.withInitial { Counts() }
    private val pool = permits?.let { Semaphore(it) }

    @Volatile
    var failOn: (String) -> Boolean = { false }

    fun peak(): Int = perThread.get().peak

    fun resetPeak() {
        perThread.get().peak = perThread.get().open
    }

    private fun acquire() {
        if (pool != null && !pool.tryAcquire(waitSeconds, TimeUnit.SECONDS)) {
            throw SQLTransientConnectionException("no connection within ${waitSeconds}s (fixture)", "08001")
        }
    }

    private fun open(): Connection =
        try {
            delegate.connection
        } catch (failure: SQLException) {
            pool?.release()
            throw failure
        }

    override fun getConnection(): Connection {
        acquire()
        val counts = perThread.get()
        val real = open()
        counts.open++
        counts.peak = maxOf(counts.peak, counts.open)
        val closed = AtomicBoolean(false)
        val loader = Connection::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "close" && closed.compareAndSet(false, true)) {
                counts.open--
                pool?.release()
            }
            check(!(method.name == "prepareStatement" && failOn(args[0] as String))) { "statement refused (fixture)" }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    }
}

/** A replay delivery over one counting DataSource, as a service's lanes give it. */
private class Delivery(
    val harness: OutboxHarness,
    val source: CountingDataSource,
) {
    val consumer = "submission-svc"
    val topic = "gst.canonical.canonical-version-created.v1"
    val tenants = TenantContextImpl()
    val scope = harness.fixture.eventLogScope(tenants, source)
    val inbox =
        JdbcInboxConsumer(
            ConsumerSubscription(consumer, topic, "submission"),
            harness.sequencedCatalogue,
            harness.reader,
            scope,
            JooqKit.module("submission", tenants, scope.transactions),
            ModeHandler(),
            harness.rebuildSink,
        )
    val dsl = JooqKit.module("submission", tenants, scope.transactions)
    val limiter = LaneRateLimiter(scope, dsl, "submission", LaneLimits())
    val queue = JdbcWorkQueue(scope, dsl, "submission", limiter, MutableClock(), consumer)
    val delivery = JdbcReplayDelivery(queue, scope, inbox)
    private val feeder = QueueKit(harness, consumer = consumer)

    /** Queues a replay item for [tenant] holding [raw]. */
    fun enqueue(
        tenant: UUID,
        raw: String,
    ): Long =
        feeder.kit.inTx(tenant) {
            feeder.queue.enqueue(
                QueueEntry(
                    UUID.randomUUID().toString(),
                    Lane.LIVE,
                    payload(raw),
                    consumer,
                ),
            )
        }

    fun pending() = harness.count("select count(*) from submission.work_queue where state = 'PENDING'")

    private fun payload(raw: String) =
        ObjectMapper()
            .createObjectNode()
            .put("deadLetterId", 1)
            .put("topic", topic)
            .put("raw", raw)
            .toString()

    fun items() = harness.count("select count(*) from submission.work_queue")

    fun letters() = harness.count("select count(*) from submission.event_dead_letter")

    /** What the handler does with an event: its data names the mode. */
    class ModeHandler : EventHandler {
        override fun handle(event: Envelope): HandleOutcome =
            when {
                event.dataJson.contains("\"throw\"") -> throw IllegalStateException("handler failed (fixture)")
                event.dataJson.contains("\"ignore\"") -> HandleOutcome.IGNORED
                else -> HandleOutcome.APPLIED
            }
    }
}

@Tag("integration")
class ReplayDeliveryConnectionsIT {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val json = EnvelopeJson()

    private fun envelope(
        tenant: UUID,
        mode: String = "ok",
        sequence: Long = 1,
        doc: UUID = UUID.randomUUID(),
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/canonical-svc",
        type = TestCatalogue.CANONICAL_VERSION_CREATED,
        subject = doc.toString(),
        time = Instant.parse("2026-10-01T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = DocUuid(doc),
        transactionDate = LocalDate.of(2026, 9, 30),
        docSequence = sequence,
        instanceId = "canonical-svc-1",
        correlation = CorrelationId("corr"),
        causation = CausationId("cause"),
        dataJson = """{"mode":"$mode"}""",
    )

    private val incomplete get() = """{"specversion":"1.0","tenantid":"$tenantA","subject":"x"}"""

    /** One delivery per input; each yields the outcome the same input gives on main (compared by running it there). */
    private fun inputs(): List<Triple<String, String, ReplayOutcome?>> {
        val sameDoc = UUID.randomUUID()
        val dup = json.write(envelope(tenantA))
        return listOf(
            Triple("applied", json.write(envelope(tenantA)), ReplayOutcome.APPLIED),
            Triple("ignored", json.write(envelope(tenantA, "ignore")), ReplayOutcome.IGNORED),
            Triple("duplicate-first", dup, ReplayOutcome.APPLIED),
            Triple("duplicate", dup, ReplayOutcome.DUPLICATE),
            Triple("stale-first", json.write(envelope(tenantA, sequence = 5, doc = sameDoc)), ReplayOutcome.APPLIED),
            Triple("stale", json.write(envelope(tenantA, sequence = 3, doc = sameDoc)), ReplayOutcome.STALE),
            Triple("invalid-json", "{not json", ReplayOutcome.REJECTED),
            Triple("incomplete", incomplete, ReplayOutcome.REJECTED),
            Triple("foreign-tenant", json.write(envelope(tenantB)), ReplayOutcome.REJECTED),
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `outcomes and dead-letter rows are those of a delivery that applies in the item's transaction`(image: String) {
        OutboxHarness(image).use { harness ->
            val d = Delivery(harness, CountingDataSource(harness.fixture.runtimeDataSource()))
            for ((name, raw, expected) in inputs()) {
                d.enqueue(tenantA, raw)
                assertEquals(expected, d.delivery.runOnce(), name)
                assertEquals(0, d.items(), "$name: completion deletes the item")
            }
            // three rejected envelopes, one letter each: consumer, topic, replay offset 0, tenant, reason, raw
            assertEquals(3, d.letters())
            val rows =
                harness.fixture.adminDataSource().connection.use { c ->
                    c
                        .prepareStatement(
                            "select consumer, topic, log_offset, tenant_id, reason, raw " +
                                "from submission.event_dead_letter order by id",
                        ).use { st ->
                            st.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add((1..6).map { rs.getObject(it) })
                                    }
                                }
                            }
                        }
                }
            val reasons = rows.map { it[4] as String }
            assertEquals(listOf("{not json", incomplete), rows.take(2).map { it[5] })
            assertEquals(listOf(d.consumer, d.topic, 0L), rows.map { it.take(3) }.distinct().single())
            assertTrue(reasons[0].isNotBlank() && reasons[1].isNotBlank())
            assertEquals("replayed envelope is for another tenant", reasons[2])
            assertEquals(tenantB, rows[2][3]) // the letter carries the envelope's own tenant
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a handler that throws releases the item for delivery again`(image: String) {
        OutboxHarness(image).use { harness ->
            val d = Delivery(harness, CountingDataSource(harness.fixture.runtimeDataSource()))
            d.enqueue(tenantA, json.write(envelope(tenantA, "throw")))
            assertThrows(IllegalStateException::class.java) { d.delivery.runOnce() }
            assertEquals(1, d.items())
            assertEquals(0, d.letters())
            assertNotNull(d.queue.claimNext(), "claimable again")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `one delivery holds one connection at a time on every path`(image: String) {
        OutboxHarness(image).use { harness ->
            val source = CountingDataSource(harness.fixture.runtimeDataSource())
            val d = Delivery(harness, source)
            for ((name, raw, _) in inputs()) {
                d.enqueue(tenantA, raw)
                source.resetPeak()
                d.delivery.runOnce()
                assertEquals(1, source.peak(), "$name: peak connections of one runOnce()")
            }
            d.enqueue(tenantA, json.write(envelope(tenantA, "throw")))
            source.resetPeak()
            assertThrows(IllegalStateException::class.java) { d.delivery.runOnce() }
            assertEquals(1, source.peak(), "handler failure and release")
            // retry: the released item is delivered again, one connection again
            source.resetPeak()
            assertThrows(IllegalStateException::class.java) { d.delivery.runOnce() }
            assertEquals(1, source.peak(), "retry")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a look-alike that records the dead letter inside the payload transaction is counted as two`(image: String) {
        OutboxHarness(image).use { harness ->
            val source = CountingDataSource(harness.fixture.runtimeDataSource())
            val d = Delivery(harness, source)
            d.enqueue(tenantA, "{not json")
            val item = checkNotNull(d.queue.claimNext())
            source.resetPeak()
            d.scope.bindWorkload(item.tenant) {
                d.scope.transactions.inTransaction {
                    d.queue.payloadOf(item)
                    // dead-letters on the claim connection inside the open transaction
                    d.inbox.applyReplayed("{not json", item.tenant)
                }
            }
            assertEquals(2, source.peak())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an invalid envelope is dead-lettered while another thread holds a lane permit`(image: String) {
        OutboxHarness(image).use { harness ->
            val source = CountingDataSource(harness.fixture.runtimeDataSource(), permits = 2)
            val d = Delivery(harness, source)
            d.enqueue(tenantA, "{not json")
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    source.connection.use {
                        held.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            holder.start()
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS))
                val started = System.nanoTime()
                assertEquals(ReplayOutcome.REJECTED, d.delivery.runOnce())
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                assertTrue(seconds < 5, "the delivery took ${seconds}s")
                assertEquals(1, d.letters())
                assertEquals(0, d.items())
            } finally {
                release.countDown()
                holder.join()
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an item is completed only after its dead letter is recorded`(image: String) {
        OutboxHarness(image).use { harness ->
            val source = CountingDataSource(harness.fixture.runtimeDataSource())
            val d = Delivery(harness, source)

            // the dead-letter function fails: the item stays, no letter exists
            d.enqueue(tenantA, "{not json")
            source.failOn = { it.contains(OutboxSchema.DEAD_LETTER_RECORD) }
            assertThrows(IllegalStateException::class.java) { d.delivery.runOnce() }
            assertEquals(1, d.items())
            assertEquals(0, d.letters())
            assertEquals(1, d.pending(), "claimable again")

            // the completion fails after the dead letter is recorded: the item is claimable, the letter stands
            source.failOn = { it.startsWith("delete from \"submission\".\"work_queue\"") }
            assertThrows(IllegalStateException::class.java) { d.delivery.runOnce() }
            assertEquals(1, d.items())
            assertEquals(1, d.letters(), "letters after a failed completion (main gives the same number)")
            source.failOn = { false }
            assertEquals(1, d.pending(), "claimable again")
        }
    }
}
