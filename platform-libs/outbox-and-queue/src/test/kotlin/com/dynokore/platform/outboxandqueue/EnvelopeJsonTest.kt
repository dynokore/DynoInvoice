package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The CloudEvents structured form: a stamped envelope round-trips, an incomplete one is a [ContractViolation]. */
class EnvelopeJsonTest {
    private val json = EnvelopeJson()
    private val doc = UUID.randomUUID()
    private val envelope =
        Envelope(
            id = EventId(UuidV7().next(Instant.parse("2026-10-01T00:00:00Z")).toString()),
            source = "/gst/payload-svc",
            type = TestCatalogue.PAYLOAD_READY,
            subject = doc.toString(),
            time = Instant.parse("2026-10-01T00:00:00Z"),
            tenant = TenantId(UUID.randomUUID()),
            entity = EntityRef("entity-1"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 9, 30),
            docSequence = 4,
            instanceId = "payload-svc-1",
            correlation = CorrelationId("c"),
            causation = CausationId("k"),
            dataJson = """{"version":2}""",
        )

    @Test
    fun `an envelope round-trips through its CloudEvents JSON`() {
        assertEquals(envelope, json.read(json.write(envelope)))
    }

    @Test
    fun `a record missing a required attribute is a contract violation`() {
        listOf("tenantid", "entityref", "instanceid", "correlationid", "causationid", "id", "type").forEach { field ->
            val text = json.write(envelope).replace("\"$field\"", "\"x$field\"")
            assertThrows(ContractViolation::class.java) { json.read(text) }
        }
        assertThrows(ContractViolation::class.java) { json.read("[]") }
        assertThrows(ContractViolation::class.java) { json.read("{not json") }
    }

    @Test
    fun `event ids are UUIDv7 and sort by time`() {
        val ids = UuidV7()
        val early = ids.next(Instant.parse("2026-10-01T00:00:00Z"))
        val late = ids.next(Instant.parse("2026-10-01T00:00:01Z"))
        assertEquals(7, early.version())
        assertEquals(2, early.variant())
        assertTrue(early.toString() < late.toString())
    }

    @Test
    fun `a document-scoped event is keyed by its Doc UUID`() {
        assertThrows(IllegalArgumentException::class.java) {
            EventContext(
                TenantId(UUID.randomUUID()),
                EntityRef("e"),
                DocUuid(doc),
                null,
                "other-key",
                null,
                CorrelationId("c"),
                CausationId("k"),
            )
        }
    }

    @Test
    fun `topic table names are plain identifiers`() {
        assertEquals("gst_payload_payload_ready_v1", EventLogSchema.tableName("gst.payload.payload-ready.v1"))
        assertThrows(IllegalArgumentException::class.java) { EventLogSchema.tableName("gst.x; drop table y") }
    }
}
