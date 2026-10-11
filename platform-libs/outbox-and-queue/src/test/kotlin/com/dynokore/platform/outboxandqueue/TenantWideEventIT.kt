package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The reserved tenant-wide entity (Keel INV-Q-3165, shaped like INV-Q-3014), proved on PostgreSQL 17 and 18:
 * a platform event of a real tenant stores [EntityRef.TENANT_WIDE] in `entity_ref` (NOT NULL, forced RLS
 * unchanged), travels with `tenantid` and no `entityref`, and is mapped back on consume. A domain event can
 * neither be written with it nor be consumed without `entityref` (INV-Q-3018, INV-REQ-1162).
 */
@Tag("integration")
class TenantWideEventIT {
    private val auditTopic = "gst.platform.audit-entry-recorded.v1"
    private val auditLog = "eventlog.gst_platform_audit_entry_recorded_v1"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a tenant-wide platform event is stored, travels without entityref and maps back`(image: String) {
        OutboxHarness(image).use { harness ->
            val producer = harness.payload
            val tenant = UUID.randomUUID()
            producer.write(tenant, audit("tenant-wide"), EntityRef.TENANT_WIDE)
            producer.write(tenant, audit("entity"), EntityRef("entity-1"))
            harness.drain(producer.relay)

            // Stored: NOT NULL entity_ref holds the reserved value; forced RLS on the table.
            val stored = "select count(*) from payload.outbox where entity_ref = '${EntityRef.TENANT_WIDE}'"
            assertEquals(1, harness.count(stored))
            assertEquals(
                1,
                harness.count(
                    "select count(*) from information_schema.columns where table_schema = 'payload' " +
                        "and table_name = 'outbox' and column_name = 'entity_ref' and is_nullable = 'NO'",
                ),
            )
            assertEquals(listOf(true, true), harness.flags("payload", "outbox"))

            // Wire: tenantid kept, entityref left out for the tenant-wide record only.
            val wire = harness.wire(auditLog)
            val wide = wire.single { it.get("data").get("who").asString() == "tenant-wide" }
            val real = wire.single { it.get("data").get("who").asString() == "entity" }
            assertEquals(tenant.toString(), wide.get("tenantid").asString())
            assertFalse(wide.has("entityref"), "tenant-wide wire: $wide")
            assertEquals("entity-1", real.get("entityref").asString())

            // Consume: absence maps back to TENANT_WIDE under the real tenant.
            val kit = harness.reconciliation
            val seen = CopyOnWriteArrayList<Triple<TenantId, EntityRef, TenantId>>()
            val handler = object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    seen += Triple(event.tenant, event.entity, kit.tenants.current())
                    return HandleOutcome.APPLIED
                }
            }
            val consumer = harness.consumer("evidence-svc", auditTopic, "reconciliation", kit.boundary, handler)
            assertEquals(2, consumer.pollOnce().applied)
            assertEquals(
                setOf(
                    Triple(TenantId(tenant), EntityRef.TENANT_WIDE, TenantId(tenant)),
                    Triple(TenantId(tenant), EntityRef("entity-1"), TenantId(tenant)),
                ),
                seen.toSet(),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a domain event cannot carry the tenant-wide entity at write nor lack entityref at consume`(image: String) {
        OutboxHarness(image).use { harness ->
            val producer = harness.payload
            val tenant = UUID.randomUUID()
            assertThrows(EmissionNotPermitted::class.java) {
                producer.inTx(tenant) {
                    producer.writer.append(
                        listOf(audit("x"), OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                        context(tenant, EntityRef.TENANT_WIDE),
                    )
                }
            }
            assertEquals(0, harness.count("select count(*) from payload.outbox"))
            // The pairing rule holds at construction: the platform tenant never takes TENANT_WIDE.
            val platform = TenantId.PLATFORM.value
            assertThrows(IllegalArgumentException::class.java) { context(platform, EntityRef.TENANT_WIDE) }

            val doc = UUID.randomUUID()
            val record = tenantRecordWithoutEntity(doc, tenant)
            harness.fixture.runAsAdmin(
                "insert into eventlog.gst_payload_payload_ready_v1 (event_id, subject, envelope) " +
                    "values ('d', '$doc', '${harness.mapper.writeValueAsString(record)}')",
            )
            val kit = harness.submission
            val consumer = harness.consumer(
                "submission-svc",
                "gst.payload.payload-ready.v1",
                "submission",
                kit.boundary,
                SubmissionFixtureHandler(kit.boundary, TransportStub()),
            )
            assertEquals(1, consumer.pollOnce().deadLettered)
            assertEquals(0, harness.count("select count(*) from submission.inbox"))
        }
    }

    private fun tenantRecordWithoutEntity(doc: UUID, tenant: UUID) = MAPPER.createObjectNode().apply {
        put("specversion", "1.0")
        put("id", UUID.randomUUID().toString())
        put("source", "/gst/payload-svc")
        put("type", TestCatalogue.PAYLOAD_READY)
        put("subject", doc.toString())
        put("time", "2026-10-01T00:00:00Z")
        put("datacontenttype", "application/json")
        put("tenantid", tenant.toString())
        put("instanceid", "payload-svc-1")
        put("correlationid", "c")
        put("causationid", "k")
        set("data", MAPPER.readTree("""{"version":1}"""))
    }

    private fun ServiceKit.write(tenant: UUID, event: OutboundEvent, entity: EntityRef) {
        val context = context(tenant, entity)
        inTx(tenant) { writer.append(listOf(event), context) }
    }

    private fun audit(who: String) = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, """{"who":"$who"}""")

    private fun context(tenant: UUID, entity: EntityRef) = EventContext(
        tenant = TenantId(tenant),
        entity = entity,
        docUuid = null,
        transactionDate = LocalDate.of(2026, 9, 30),
        aggregateKey = "job-a",
        aggregateVersion = 1,
        correlation = CorrelationId("corr-$tenant"),
        causation = CausationId("cmd-$tenant"),
    )

    private companion object {
        val MAPPER = tools.jackson.databind.ObjectMapper()
    }
}
