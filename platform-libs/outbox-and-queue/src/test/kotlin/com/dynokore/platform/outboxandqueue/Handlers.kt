package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** What `transport-svc` would have been called with (INV-AC-2149): the fixture records each call. */
class TransportStub {
    val calls = CopyOnWriteArrayList<Pair<UUID, Int>>()
}

/**
 * Plays `submission-svc`'s consumer side (TPL-06): creates the obligation register entry on the
 * first event whatever its type, completes it on the second (INV-REQ-1221), and on a `PayloadReady`
 * makes one submission and one queue item and calls `transport-svc` once (INV-REQ-816, INV-AC-2149).
 */
class SubmissionFixtureHandler(
    private val connections: CurrentConnection,
    private val transport: TransportStub,
    private val mapper: ObjectMapper = ObjectMapper(),
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val doc = UUID.fromString(event.subject)
        val canonical = event.type == TestCatalogue.CANONICAL_VERSION_CREATED
        val payload = event.type == TestCatalogue.PAYLOAD_READY
        connections.require().prepareStatement(
            """
            insert into submission.obligation_register (doc_uuid, canonical_seen, payload_seen) values (?, ?, ?)
            on conflict (doc_uuid) do update set
                canonical_seen = submission.obligation_register.canonical_seen or excluded.canonical_seen,
                payload_seen = submission.obligation_register.payload_seen or excluded.payload_seen
            """.trimIndent(),
        ).use {
            it.setObject(1, doc)
            it.setBoolean(2, canonical)
            it.setBoolean(3, payload)
            it.executeUpdate()
        }
        if (payload) despatchOnce(doc, mapper.readTree(event.dataJson).get("version").asInt())
        return HandleOutcome.APPLIED
    }

    /** Unique (doc, version) keys: a second PayloadReady of the same version makes no second queue item or call. */
    private fun despatchOnce(
        doc: UUID,
        version: Int,
    ) {
        val inserted =
            listOf("queue_item", "submission_row").map { table ->
                connections.require().prepareStatement(
                    "insert into submission.$table (doc_uuid, version) values (?, ?) on conflict do nothing",
                ).use {
                    it.setObject(1, doc)
                    it.setInt(2, version)
                    it.executeUpdate()
                }
            }
        if (inserted.all { it == 1 }) transport.calls += doc to version
    }
}

/**
 * Plays `reconciliation-svc`'s consumer side: a read model that counts acknowledgements, so a
 * double application is visible as a count of two (INV-AC-2205). [afterUpdate] runs after the
 * state change and before the transaction commits.
 */
class ReconciliationFixtureHandler(
    private val connections: CurrentConnection,
    private val afterUpdate: () -> Unit = {},
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        connections.require().prepareStatement(
            """
            insert into reconciliation.ack_read_model (doc_uuid, ack_count) values (?, 1)
            on conflict (doc_uuid) do update set ack_count = reconciliation.ack_read_model.ack_count + 1
            """.trimIndent(),
        ).use {
            it.setObject(1, UUID.fromString(event.subject))
            it.executeUpdate()
        }
        afterUpdate()
        return HandleOutcome.APPLIED
    }
}
