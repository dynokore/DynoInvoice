package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

/**
 * The relay and the event log. `payload-svc` and `submission-svc` are fixtures here.
 * INV-AC-2185 (outage: producers keep committing, the relay catches up in per-key order, consumers
 * apply each event once), INV-AC-2147 (`docsequence` grows per document across the producer's events)
 * and the event log's own grants (INV-REQ-1205).
 */
@Tag("integration")
class RelayOutageIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `with the event log down producers keep committing and the relay catches up in order`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val docs = List(DOCS) { UUID.randomUUID() }
            harness.eventLogForRelay.available = false

            // "Ten minutes" of recording: three commands per document, interleaved, relay trying throughout.
            repeat(EVENTS_PER_DOC) { round ->
                docs.forEach { doc ->
                    kit.inTx(tenant) {
                        kit.writer.append(
                            listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":${round + 1}}""")),
                            kit.docContext(tenant, doc),
                        )
                    }
                }
                val outcome = kit.relay.runOnce()
                assertTrue(outcome.stalled)
                assertEquals(0, outcome.published)
            }
            assertEquals(
                (DOCS * EVENTS_PER_DOC).toLong(),
                harness.count("select count(*) from payload.outbox where published_at is null"),
            )

            harness.eventLogForRelay.available = true
            harness.drain(kit.relay)

            assertEquals(0, harness.count("select count(*) from payload.outbox where published_at is null"))
            val log = harness.logOf(PAYLOAD_READY_TOPIC)
            assertEquals(DOCS * EVENTS_PER_DOC, log.size)
            docs.forEach { doc ->
                val sequences = log.filter { it.subject == doc.toString() }.map { it.docSequence }
                assertEquals(listOf(1L, 2L, 3L), sequences) // per-key order preserved across the outage
                assertEquals(
                    listOf(1, 2, 3),
                    log.filter { it.subject == doc.toString() }.map { versionOf(harness, it) },
                )
            }
            assertEquals(DOCS * EVENTS_PER_DOC, log.map { it.id }.toSet().size)

            assertEachAppliedOnce(harness, DOCS * EVENTS_PER_DOC)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a relay crash after publish republishes the same ids and consumers apply each event once`(image: String) {
        OutboxHarness(image).use { harness ->
            val producer = harness.payload
            val doc = UUID.randomUUID()
            producer.inTx(ServiceKit.TENANT) {
                producer.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                    producer.docContext(ServiceKit.TENANT, doc),
                )
            }
            harness.drain(producer.relay)
            // Crash between publish and the published_at commit: the row is still unpublished, so it goes out again.
            harness.fixture.runAsAdmin("update payload.outbox set published_at = null")
            harness.drain(producer.relay)

            val log = harness.logOf(PAYLOAD_READY_TOPIC)
            assertEquals(2, log.size)
            assertEquals(1, log.map { it.id }.toSet().size) // same id: the relay never mints one

            val consumer = harness.submission
            val transport = TransportStub()
            val inbox = harness.consumer(
                "submission-svc",
                PAYLOAD_READY_TOPIC,
                "submission",
                consumer.boundary,
                SubmissionFixtureHandler(consumer.boundary, transport),
            )
            val result = inbox.pollOnce()
            assertEquals(1, result.applied)
            assertEquals(1, result.duplicates)
            assertEquals(1, transport.calls.size)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `docsequence is monotonic per document across a producer's events and honours a higher version`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val doc = UUID.randomUUID()
            val a = kit.inTx(tenant) {
                kit.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")),
                    kit.docContext(tenant, doc),
                )
            }
            val b = kit.inTx(tenant) {
                kit.writer.append(
                    listOf(
                        OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}"),
                        OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}"),
                    ),
                    kit.docContext(tenant, doc),
                )
            }
            val c = kit.inTx(tenant) {
                kit.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")),
                    kit.docContext(tenant, doc).copy(aggregateVersion = 10),
                )
            }
            val d = kit.inTx(tenant) {
                kit.writer.append(
                    listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")),
                    kit.docContext(tenant, doc).copy(aggregateVersion = 3),
                )
            }

            assertEquals(listOf(1L, 2L, 3L, 10L, 11L), (a + b + c + d).map { it.docSequence })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the event log grants are relay insert and consumer select only`(image: String) {
        OutboxHarness(image).use { harness ->
            val table = "eventlog.gst_payload_payload_ready_v1"
            harness.fixture.relayDataSource().connection.use { connection ->
                assertThrows(SQLException::class.java) {
                    connection.createStatement().use { it.executeQuery("select * from $table") }
                }
            }
            harness.fixture.readerDataSource().connection.use { connection ->
                assertThrows(SQLException::class.java) {
                    connection.createStatement().use {
                        it.executeUpdate(
                            "insert into $table (event_id, subject, envelope) values ('x', 'y', '{}')",
                        )
                    }
                }
                assertFalse(connection.createStatement().use { it.executeQuery("select * from $table").next() })
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `outbox rows are tenant-isolated and the worker role sees only the claim columns`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val doc = UUID.randomUUID()
            val owner = UUID.randomUUID()
            kit.inTx(owner) {
                kit.writer.append(listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")), kit.docContext(owner, doc))
            }
            val seen = { tenant: UUID -> kit.inTx(tenant) { rowsVisible(kit) } }
            assertEquals(1, seen(owner))
            assertEquals(0, seen(UUID.randomUUID()))
            harness.fixture.workerDataSource().connection.use { connection ->
                connection.createStatement().use {
                    assertTrue(it.executeQuery("select seq, tenant_id from payload.outbox").next())
                    assertThrows(SQLException::class.java) { it.executeQuery("select data from payload.outbox") }
                }
            }
        }
    }

    /** Consumers apply each event once: every event reaches the handler, none twice. */
    private fun assertEachAppliedOnce(
        harness: OutboxHarness,
        expected: Int,
    ) {
        val kit = harness.submission
        val transport = TransportStub()
        val handler = SubmissionFixtureHandler(kit.boundary, transport)
        val consumer = harness.consumer("submission-svc", PAYLOAD_READY_TOPIC, "submission", kit.boundary, handler)
        assertEquals(expected, consumer.pollOnce().applied)
        assertEquals(expected, transport.calls.toSet().size)
        assertEquals(expected, transport.calls.size)
        assertEquals(0, consumer.pollOnce().total)
    }

    private fun rowsVisible(kit: ServiceKit): Int = kit.boundary.require().createStatement().use { statement ->
        statement.executeQuery("select count(*) from payload.outbox").use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun versionOf(
        harness: OutboxHarness,
        event: Envelope,
    ): Int = harness.mapper.readTree(event.dataJson).get("version").asInt()

    private companion object {
        const val DOCS = 6
        const val EVENTS_PER_DOC = 3
        const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"
    }
}
