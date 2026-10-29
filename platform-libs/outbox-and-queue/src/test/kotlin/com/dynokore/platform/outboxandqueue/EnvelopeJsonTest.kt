package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    private fun withTestmode(value: String) = json.write(envelope).dropLast(1) + ""","testmode":$value}"""

    @Test
    fun `a default envelope is written without testmode and a test envelope with the boolean true`() {
        assertFalse(json.write(envelope).contains("testmode"))
        assertTrue(json.write(envelope.copy(testMode = true)).contains(""""testmode":true"""))
    }

    @Test
    fun `a test envelope round-trips`() {
        val test = envelope.copy(testMode = true)
        assertEquals(test, json.read(json.write(test)))
        assertTrue(json.read(json.write(test)).testMode)
    }

    @Test
    fun `an absent testmode reads as false`() {
        assertFalse(json.read(json.write(envelope)).testMode)
    }

    @Test
    fun `testmode true reads as true`() {
        assertTrue(json.read(withTestmode("true")).testMode)
    }

    @Test
    fun `testmode false reads as false`() {
        assertFalse(json.read(withTestmode("false")).testMode)
    }

    @Test
    fun `testmode as the string true is a contract violation`() {
        val failure = assertThrows(ContractViolation::class.java) { json.read(withTestmode("\"true\"")) }
        assertTrue(failure.message!!.contains("testmode"))
    }

    @Test
    fun `testmode as the integer 1 is a contract violation`() {
        val failure = assertThrows(ContractViolation::class.java) { json.read(withTestmode("1")) }
        assertTrue(failure.message!!.contains("testmode"))
    }

    @Test
    fun `testmode as null is a contract violation`() {
        val failure = assertThrows(ContractViolation::class.java) { json.read(withTestmode("null")) }
        assertTrue(failure.message!!.contains("testmode"))
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

    private val platformEnvelope =
        envelope.copy(
            type = TestCatalogue.AUDIT_ENTRY_RECORDED,
            subject = "job-1",
            docUuid = null,
            docSequence = 1,
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
        )

    @Test
    fun `a platform envelope leaves tenantid and entityref off the wire and reads back as the platform`() {
        val text = json.write(platformEnvelope)
        assertFalse(text.contains("tenantid") || text.contains("entityref") || text.contains("ffffffff"), text)
        assertEquals(platformEnvelope, json.read(text))
        assertEquals(TenantId.PLATFORM, json.tenantOf(text))
    }

    @Test
    fun `only a platform type may omit the tenant and the reserved ids never travel`() {
        val tenantless =
            json
                .write(envelope)
                .replace("\"tenantid\"", "\"xtenantid\"")
                .replace("\"entityref\"", "\"xentityref\"")
        assertThrows(ContractViolation::class.java) { json.read(tenantless) }
        assertEquals(null, json.tenantOf(tenantless))
        val named = json.write(envelope).replace(envelope.tenant.toString(), TenantId.PLATFORM.toString())
        assertThrows(ContractViolation::class.java) { json.read(named) }
        assertEquals(null, json.tenantOf(named))
        val withEntity = json.write(platformEnvelope).replace("\"instanceid\"", "\"entityref\":\"e\",\"instanceid\"")
        assertThrows(ContractViolation::class.java) { json.read(withEntity) }
    }

    @Test
    fun `the platform tenant and the platform entity always go together`() {
        assertThrows(IllegalArgumentException::class.java) { platformEnvelope.copy(entity = EntityRef("e")) }
        assertThrows(IllegalArgumentException::class.java) { envelope.copy(entity = EntityRef.PLATFORM) }
        assertThrows(IllegalArgumentException::class.java) { platformEnvelope.copy(type = TestCatalogue.PAYLOAD_READY) }
        assertThrows(IllegalArgumentException::class.java) {
            EventContext(TenantId.PLATFORM, EntityRef("e"), null, null, "k", null, CorrelationId("c"), CausationId("k"))
        }
    }

    private val tenantWideEnvelope = platformEnvelope.copy(tenant = envelope.tenant, entity = EntityRef.TENANT_WIDE)

    @Test
    fun `a tenant-wide platform envelope keeps tenantid, omits entityref and reads back as tenant-wide`() {
        val text = json.write(tenantWideEnvelope)
        assertFalse(text.contains("entityref") || text.contains("00000000-0000"), text)
        assertEquals(envelope.tenant.toString(), json.tenantOf(text)?.toString())
        assertEquals(tenantWideEnvelope, json.read(text))
        assertNotEquals(EntityRef.PLATFORM, EntityRef.TENANT_WIDE)
    }

    @Test
    fun `a domain event without entityref is refused and the tenant-wide id never travels`() {
        val noEntity = json.write(envelope).replace("\"entityref\"", "\"xentityref\"")
        assertThrows(ContractViolation::class.java) { json.read(noEntity) }
        val reserved = "\"entityref\":\"${EntityRef.TENANT_WIDE}\",\"instanceid\""
        val named = json.write(tenantWideEnvelope).replace("\"instanceid\"", reserved)
        assertThrows(ContractViolation::class.java) { json.read(named) }
    }

    @Test
    fun `the tenant-wide entity pairs with a real tenant and a platform type only`() {
        assertThrows(IllegalArgumentException::class.java) { tenantWideEnvelope.copy(tenant = TenantId.PLATFORM) }
        assertThrows(IllegalArgumentException::class.java) { envelope.copy(entity = EntityRef.TENANT_WIDE) }
        assertThrows(IllegalArgumentException::class.java) {
            val wide = EntityRef.TENANT_WIDE
            EventContext(TenantId.PLATFORM, wide, null, null, "k", null, CorrelationId("c"), CausationId("k"))
        }
    }
}
