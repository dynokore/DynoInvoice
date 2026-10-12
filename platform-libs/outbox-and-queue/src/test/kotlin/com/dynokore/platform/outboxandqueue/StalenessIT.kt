package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** INV-AC-2150: the oldest unconsumed event passes its threshold, and not before. The clock is injected. */
@Tag("integration")
class StalenessIT {
    private val topic = "gst.canonical.canonical-version-created.v1"
    private val table = "eventlog.gst_canonical_canonical_version_created_v1"
    private val threshold = Duration.ofMinutes(5)
    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    private fun emit(
        harness: OutboxHarness,
        tenant: UUID,
    ) {
        val canonical = harness.canonical
        canonical.inTx(tenant) {
            val doc = UUID.randomUUID()
            canonical.writer.append(
                listOf(OutboundEvent(TestCatalogue.CANONICAL_VERSION_CREATED, """{"version":1}""")),
                canonical.docContext(tenant, doc),
            )
        }
        harness.drain(canonical.relay)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a stopped consumer raises one breach naming consumer, event type and lag past its threshold`(image: String) {
        OutboxHarness(image).use { harness ->
            val clock = MutableClock(t0)
            val raised = mutableListOf<StalenessBreach>()
            val monitor = monitorFor(harness, clock, raised)
            assertEquals(Duration.ZERO, monitor.check().single().lag, "nothing unconsumed: no lag")

            // submission-svc is stopped; events keep arriving. The oldest was inserted at t0.
            repeat(3) { emit(harness, ServiceKit.TENANT) }
            harness.fixture.runAsAdmin(
                "update $table set inserted_at = timestamptz '$t0' + log_offset * interval '1 second'",
            )

            clock.advance(threshold) // exactly the threshold, less the offset's seconds: not past it
            val atThreshold = monitor.check().single()
            assertEquals(3, atThreshold.backlog)
            assertTrue(!atThreshold.breached && raised.isEmpty(), "no incident before the threshold")

            clock.advance(Duration.ofMinutes(1))
            val late = monitor.check().single()
            assertTrue(late.breached)
            val breach = raised.single()
            assertEquals("submission-svc", breach.consumer)
            assertEquals(TestCatalogue.CANONICAL_VERSION_CREATED, breach.eventType)
            assertEquals(Duration.ofMinutes(6).minusSeconds(1), breach.lag, "lag of the oldest unconsumed event")
            assertEquals(threshold, breach.threshold)

            monitor.check()
            assertEquals(1, raised.size, "a continuing breach is raised once")

            // The consumer recovers, consumes everything, and a later stall raises a new breach.
            val kit = harness.submission
            val handler = SubmissionFixtureHandler(kit.boundary, TransportStub())
            harness.consumer("submission-svc", topic, "submission", kit.boundary, handler).pollOnce()
            val recovered = monitor.check().single()
            assertEquals(0, recovered.backlog)
            assertEquals(Duration.ZERO, recovered.lag)
            emit(harness, ServiceKit.TENANT)
            harness.fixture.runAsAdmin(
                "update $table set inserted_at = timestamptz '${clock.instant()}' where log_offset > 3",
            )
            clock.advance(threshold.plusSeconds(1))
            monitor.check()
            assertEquals(2, raised.size)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the galloping head equals the last inserted offset and the backlog is head less position`(image: String) {
        OutboxHarness(image).use { harness ->
            assertEquals(TopicHead(0, 0), harness.reader.head(topic, 0), "an empty topic")
            val canonical = harness.canonical
            canonical.inTx(ServiceKit.TENANT) {
                canonical.writer.append(
                    List(37) { OutboundEvent(TestCatalogue.CANONICAL_VERSION_CREATED, """{"version":$it}""") },
                    canonical.docContext(ServiceKit.TENANT, UUID.randomUUID()),
                )
            }
            harness.drain(canonical.relay)
            val last = harness.count("select max(log_offset) from $table")
            assertEquals(37L, last)
            for (from in listOf(0L, 1L, 5L, 36L, 37L)) {
                assertEquals(last, harness.reader.head(topic, from).head, "from $from")
            }
            assertEquals(LogBacklog(37 - 10, oldest(harness, 11)), harness.reader.backlog(topic, 10))
            assertEquals(LogBacklog(0, null), harness.reader.backlog(topic, 37), "nothing unconsumed")

            // After a purge the offsets below the watermark are gone: the backlog stays head less position, an
            // upper bound.
            harness.fixture.runAsAdmin("delete from $table where log_offset between 11 and 20")
            assertEquals(LogBacklog(37 - 10, oldest(harness, 21)), harness.reader.backlog(topic, 10))
        }
    }

    private fun oldest(
        harness: OutboxHarness,
        offset: Long,
    ): Instant = harness.fixture.adminDataSource().connection.use { connection ->
        connection.prepareStatement("select inserted_at from $table where log_offset = ?").use {
            it.setLong(1, offset)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getTimestamp(1).toInstant()
            }
        }
    }

    private fun monitorFor(
        harness: OutboxHarness,
        clock: MutableClock,
        raised: MutableList<StalenessBreach>,
    ) = JdbcStalenessMonitor(
        harness.fixture.runtimeDataSource(),
        harness.reader,
        harness.catalogue,
        "submission",
        listOf(StalenessRule("submission-svc", TestCatalogue.CANONICAL_VERSION_CREATED, threshold)),
        clock,
    ) { raised += it }
}
