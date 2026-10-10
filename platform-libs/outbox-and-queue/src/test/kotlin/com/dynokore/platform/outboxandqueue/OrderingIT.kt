package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * Per-aggregate ordering on the consumer side (INV-REQ-1221). `canonical-svc`, `payload-svc` and
 * `submission-svc` are fixtures. INV-AC-2204: `PayloadReady` before `CanonicalVersionCreated`
 * yields the same register state as the natural order.
 */
@Tag("integration")
class OrderingIT {
    private data class Register(val canonical: Boolean, val payload: Boolean, val state: String)

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `PayloadReady before CanonicalVersionCreated yields the same register state as natural order`(image: String) {
        OutboxHarness(image).use { harness ->
            val tenant = UUID.randomUUID()
            val natural = UUID.randomUUID()
            val reversed = UUID.randomUUID()
            val canonicalTopic = "gst.canonical.canonical-version-created.v1"
            val payloadTopic = "gst.payload.payload-ready.v1"
            // Natural order: canonical first, then payload.
            emit(harness, harness.canonical, TestCatalogue.CANONICAL_VERSION_CREATED, tenant, natural)
            consume(harness, canonicalTopic)
            emit(harness, harness.payload, TestCatalogue.PAYLOAD_READY, tenant, natural)
            consume(harness, payloadTopic)
            // Reversed: the payload arrives first and creates the register entry; canonical completes it.
            emit(harness, harness.payload, TestCatalogue.PAYLOAD_READY, tenant, reversed)
            consume(harness, payloadTopic)
            assertEquals(Register(canonical = false, payload = true, state = "PARTIAL"), register(harness, reversed))
            emit(harness, harness.canonical, TestCatalogue.CANONICAL_VERSION_CREATED, tenant, reversed)
            consume(harness, canonicalTopic)

            val naturalState = register(harness, natural)
            assertEquals(Register(canonical = true, payload = true, state = "COMPLETE"), naturalState)
            assertEquals(naturalState, register(harness, reversed))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a docsequence not newer than the one held is ignored, and never compared across producers`(image: String) {
        OutboxHarness(image).use { harness ->
            val tenant = UUID.randomUUID()
            val doc = UUID.randomUUID()
            val low = unrelayed(harness, tenant, doc, 1)
            val high = unrelayed(harness, tenant, doc, 2)
            // The log delivers the newer event first (a re-ordered log): publish high, then low.
            val binding = PostgresEventBinding(harness.eventLogForRelay)
            binding.publish("gst.payload.payload-ready.v1", high)
            binding.publish("gst.payload.payload-ready.v1", low)

            val kit = harness.submission
            val transport = TransportStub()
            val consumer = harness.consumer(
                "submission-svc",
                "gst.payload.payload-ready.v1",
                "submission",
                kit.boundary,
                SubmissionFixtureHandler(kit.boundary, transport),
            )
            val result = consumer.pollOnce()

            assertEquals(1, result.applied)
            assertEquals(1, result.stale)
            assertEquals(listOf(doc to 2), transport.calls) // only the newer event reached the handler
            // A canonical event with docsequence 1 from another producer is not stale against payload-svc's 2.
            emit(harness, harness.canonical, TestCatalogue.CANONICAL_VERSION_CREATED, tenant, doc)
            assertEquals(1, consume(harness, "gst.canonical.canonical-version-created.v1").applied)
        }
    }

    private fun unrelayed(
        harness: OutboxHarness,
        tenant: UUID,
        doc: UUID,
        version: Int,
    ) = emit(harness, harness.payload, TestCatalogue.PAYLOAD_READY, tenant, doc, version, relay = false)

    private fun emit(
        harness: OutboxHarness,
        kit: ServiceKit,
        type: String,
        tenant: UUID,
        doc: UUID,
        version: Int = 1,
        relay: Boolean = true,
    ): Envelope {
        val envelope =
            kit.inTx(tenant) {
                kit.writer.append(
                    listOf(OutboundEvent(type, """{"version":$version}""")),
                    kit.docContext(tenant, doc),
                ).single()
            }
        if (relay) harness.drain(kit.relay)
        return envelope
    }

    private fun consume(
        harness: OutboxHarness,
        topic: String,
    ): PollResult {
        val kit = harness.submission
        val consumer = harness.consumer(
            "submission-svc",
            topic,
            "submission",
            kit.boundary,
            SubmissionFixtureHandler(kit.boundary, TransportStub()),
        )
        return consumer.pollOnce()
    }

    private fun register(
        harness: OutboxHarness,
        doc: UUID,
    ): Register = harness.fixture.adminDataSource().connection.use { connection ->
        connection.prepareStatement(
            "select canonical_seen, payload_seen, state from submission.obligation_register where doc_uuid = ?",
        ).use {
            it.setObject(1, doc)
            it.executeQuery().use { rows ->
                check(rows.next()) { "no register entry for $doc" }
                Register(rows.getBoolean(1), rows.getBoolean(2), rows.getString(3))
            }
        }
    }
}
