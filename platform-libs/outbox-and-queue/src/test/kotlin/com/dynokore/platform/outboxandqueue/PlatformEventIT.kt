package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tenantless platform events (Keel INV-Q-2927 option D, INV-Q-3014, INV-Q-3018), proved on PostgreSQL 17 and 18:
 * stored under the reserved `TenantId.PLATFORM` and [EntityRef.PLATFORM] with `tenant_id NOT NULL` and forced RLS
 * on every table (CS-19), carried on the wire with no `tenantid` or `entityref`, mapped back on consume, and
 * isolated from every real tenant in both directions. A domain event cannot travel that way.
 */
@Tag("integration")
class PlatformEventIT {
    private val auditTopic = "gst.platform.audit-entry-recorded.v1"
    private val auditLog = "eventlog.gst_platform_audit_entry_recorded_v1"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a platform event round-trips with no tenant on the wire and no cross-tenant read`(image: String) {
        OutboxHarness(image).use { harness ->
            val producer = harness.payload
            val tenant = UUID.randomUUID()
            // One platform event and one ordinary tenant's audit entry, from the same service.
            producer.inPlatformTx { producer.writer.append(listOf(audit("operator")), platformContext("job-a")) }
            producer.inTx(tenant) {
                producer.writer.append(listOf(audit("tenant")), producer.auditContext(tenant))
            }
            harness.drain(producer.relay)

            // On the wire: the platform record carries neither attribute, the tenant's carries both.
            val wire = harness.wire(auditLog)
            val platformWire = wire.single { it.get("data").get("who").asString() == "operator" }
            val tenantWire = wire.single { it.get("data").get("who").asString() == "tenant" }
            assertFalse(platformWire.has("tenantid") || platformWire.has("entityref"), "platform wire: $platformWire")
            assertEquals(tenant.toString(), tenantWire.get("tenantid").asString())

            // The consumer maps the absence back and applies it in a platform transaction.
            assertEquals(
                setOf(
                    Triple(TenantId.PLATFORM, EntityRef.PLATFORM, TenantId.PLATFORM),
                    Triple(TenantId(tenant), EntityRef("entity-1"), TenantId(tenant)),
                ),
                consumeAudit(harness),
            )
            assertPlatformRowsIsolated(harness, tenant)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a tenantless domain event is refused at write and dead-lettered at consume`(image: String) {
        OutboxHarness(image).use { harness ->
            val producer = harness.payload
            assertThrows(EmissionNotPermitted::class.java) {
                producer.inPlatformTx {
                    producer.writer.append(
                        listOf(audit("operator"), OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                        platformContext("job-b"),
                    )
                }
            }
            assertEquals(0, harness.count("select count(*) from payload.outbox"))

            val doc = UUID.randomUUID()
            harness.fixture.runAsAdmin(
                "insert into eventlog.gst_payload_payload_ready_v1 (tenant_id, event_id, subject, envelope) " +
                    "values ('${TenantId.PLATFORM}', 'd', '$doc', " +
                    "'${harness.mapper.writeValueAsString(tenantlessDomainRecord(doc))}')",
            )
            val kit = harness.submission
            val consumer =
                harness.consumer(
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

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `upgradeTo120 keeps 1_1_0 rows and lets a second tenant's same-subject event through`(image: String) {
        OutboxHarness(image).use { harness ->
            val tenant = UUID.randomUUID()
            val sequences = "select count(*) from reconciliation.consumer_sequence"
            val seeded = "$sequences where tenant_id = ? and subject = 'job-a' and last_value = 5"
            // A 1.1.0 consumer_sequence, holding one tenant's row for (evidence-svc, /gst/payload-svc, job-a).
            harness.fixture.runAsAdmin(
                "alter table reconciliation.consumer_sequence drop constraint consumer_sequence_pkey, " +
                    "add primary key (consumer, source, subject); " +
                    "insert into reconciliation.consumer_sequence (consumer, source, subject, tenant_id, last_value) " +
                    "values ('evidence-svc', '/gst/payload-svc', 'job-a', '$tenant', 5)",
            )
            val producer = harness.payload
            producer.inPlatformTx { producer.writer.append(listOf(audit("operator")), platformContext("job-a")) }
            harness.drain(producer.relay)
            val kit = harness.reconciliation
            val clock = MutableClock()
            val consumer =
                JdbcInboxConsumer(
                    ConsumerSubscription("evidence-svc", auditTopic, "reconciliation"),
                    harness.reader,
                    harness.fixture.workerDataSource(),
                    TenantScope(kit.tenants, kit.boundary, kit.boundary),
                    RecordingHandler(),
                    clock,
                )

            // 1.1.0: the platform event's sequence upsert hits the tenant's row and fails row-level security.
            assertThrows(SQLException::class.java) { consumer.pollOnce() }

            harness.fixture.runAsAdmin(OutboxSchema.upgradeTo120("reconciliation"))
            harness.fixture.runAsAdmin(OutboxSchema.upgradeTo120("reconciliation"))
            assertEquals(1, harness.count(seeded, tenant), "the 1.1.0 row survives unchanged")

            clock.advance(Duration.ofMinutes(10)) // past the retry wait
            assertEquals(1, consumer.pollOnce().applied)
            assertEquals(1, harness.count(seeded, tenant))
            val platformRow = "$sequences where tenant_id = ? and subject = 'job-a'"
            assertEquals(1, harness.count(platformRow, TenantId.PLATFORM.value))
        }
    }

    /** Records nothing; applies every event. */
    private class RecordingHandler : EventHandler {
        override fun handle(event: Envelope): HandleOutcome = HandleOutcome.APPLIED
    }

    /** Polls the audit topic once as `evidence-svc` would; each applied event's tenant, entity and bound tenant. */
    private fun consumeAudit(harness: OutboxHarness): Set<Triple<TenantId, EntityRef, TenantId>> {
        val kit = harness.reconciliation
        val seen = CopyOnWriteArrayList<Triple<TenantId, EntityRef, TenantId>>()
        val handler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    seen += Triple(event.tenant, event.entity, kit.tenants.current())
                    return HandleOutcome.APPLIED
                }
            }
        val consumer = harness.consumer("evidence-svc", auditTopic, "reconciliation", kit.boundary, handler)
        assertEquals(2, consumer.pollOnce().applied)
        return seen.toSet()
    }

    /** Storage: NOT NULL tenant_id under the reserved id, forced RLS, no cross-tenant read either way (CS-19). */
    private fun assertPlatformRowsIsolated(
        harness: OutboxHarness,
        tenant: UUID,
    ) {
        val platform = TenantId.PLATFORM
        listOf("payload.outbox", "payload.outbox_sequence", "reconciliation.inbox").forEach { table ->
            assertEquals(1, harness.rows(table, "tenant_id", platform.value), table)
            assertEquals(1, harness.rows(table, "tenant_id", tenant), table)
            val (schema, name) = table.split('.')
            assertEquals(listOf(true, true), harness.flags(schema, name), "RLS flags of $table")
            assertEquals(listOf("NO"), harness.nullable(schema, name), "tenant_id of $table is NOT NULL")
        }
        val sides = listOf(harness.payload to "payload.outbox", harness.reconciliation to "reconciliation.inbox")
        sides.forEach { (side, table) ->
            assertEquals(0, side.inTx(tenant) { side.visible(table, platform) }, "tenant sees platform $table")
            assertEquals(0, side.inPlatformTx { side.visible(table, TenantId(tenant)) }, "platform sees tenant $table")
            assertEquals(1, side.inPlatformTx { side.visible(table, platform) }, "platform sees its $table")
        }
    }

    private fun tenantlessDomainRecord(doc: UUID) = MAPPER.createObjectNode().apply {
        put("specversion", "1.0")
        put("id", UUID.randomUUID().toString())
        put("source", "/gst/payload-svc")
        put("type", TestCatalogue.PAYLOAD_READY)
        put("subject", doc.toString())
        put("time", "2026-10-01T00:00:00Z")
        put("datacontenttype", "application/json")
        put("instanceid", "payload-svc-1")
        put("correlationid", "c")
        put("causationid", "k")
        set("data", MAPPER.readTree("""{"version":1}"""))
    }

    private fun audit(who: String) = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, """{"who":"$who"}""")

    private fun platformContext(key: String) = EventContext(
        tenant = TenantId.PLATFORM,
        entity = EntityRef.PLATFORM,
        docUuid = null,
        transactionDate = null,
        aggregateKey = key,
        aggregateVersion = 1,
        correlation = CorrelationId("corr-$key"),
        causation = CausationId("cmd-$key"),
    )

    private fun ServiceKit.auditContext(tenant: UUID) = EventContext(
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = null,
        transactionDate = LocalDate.of(2026, 9, 30),
        aggregateKey = "job-a",
        aggregateVersion = 1,
        correlation = CorrelationId("corr-$tenant"),
        causation = CausationId("cmd-$service"),
    )

    private fun <T> ServiceKit.inPlatformTx(block: () -> T): T = tenants.bindPlatform { boundary.inTransaction(block) }

    /** Rows of [table] with [owner]'s tenant id visible in the open transaction. */
    private fun ServiceKit.visible(
        table: String,
        owner: TenantId,
    ): Int = boundary.require().prepareStatement("select count(*) from $table where tenant_id = ?").use {
        it.setObject(1, owner.value)
        it.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private companion object {
        val MAPPER = tools.jackson.databind.ObjectMapper()
    }
}
