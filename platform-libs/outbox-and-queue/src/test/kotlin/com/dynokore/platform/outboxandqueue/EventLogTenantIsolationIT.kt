package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Tenant row-level security on the event-log topic tables (Keel INV-T-3300, INV-Q-3251 option D, INV-REQ-854,
 * CS-19, ARC 4.3 step 1a), proved on PostgreSQL 17 and 18: `tenant_id NOT NULL` under forced RLS; the relay inserts
 * only under the bound tenant; consumers claim on the claim columns and read envelopes only of the bound tenant;
 * tenant and platform events round-trip in `log_offset` order.
 */
@Tag("integration")
class EventLogTenantIsolationIT {
    private val topic = "gst.platform.audit-entry-recorded.v1"
    private val table = "eventlog.gst_platform_audit_entry_recorded_v1"
    private val mapper = tools.jackson.databind.ObjectMapper()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `topic tables carry a mandatory tenant under forced RLS and no role bypasses it`(image: String) {
        OutboxHarness(image).use { harness ->
            assertEquals(listOf(true, true), harness.flags("eventlog", "gst_platform_audit_entry_recorded_v1"))
            assertEquals(listOf("NO"), harness.nullable("eventlog", "gst_platform_audit_entry_recorded_v1"))
            assertEquals(
                0,
                harness.count(
                    "select count(*) from pg_roles " +
                        "where (rolname like 'outbox_%' or rolname like 'eventlog_%') and rolbypassrls",
                ),
                "no role has BYPASSRLS",
            )
        }
    }

    // INV-T-3999 removes this call
    @Suppress("DEPRECATION")
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `tenant and platform events round-trip in offset order, each read only under its own tenant`(image: String) {
        OutboxHarness(image).use { harness ->
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val producer = harness.payload
            producer.inTx(a) { producer.writer.append(listOf(audit("a1")), producer.context(TenantId(a), "k-a1")) }
            producer.tenants.bindPlatform {
                producer.boundary.inTransaction {
                    producer.writer.append(listOf(audit("p1")), producer.context(TenantId.PLATFORM, "k-p1"))
                }
            }
            producer.inTx(b) { producer.writer.append(listOf(audit("b1")), producer.context(TenantId(b), "k-b1")) }
            producer.inTx(a) { producer.writer.append(listOf(audit("a2")), producer.context(TenantId(a), "k-a2")) }
            harness.drain(producer.relay)

            val records = harness.reader.read(topic, 0, OutboxHarness.LOG_LIMIT)
            assertEquals(records.map { it.offset }.sorted(), records.map { it.offset }, "log_offset order")
            val json = EnvelopeJson()
            assertEquals(
                listOf("a1" to TenantId(a), "p1" to TenantId.PLATFORM, "b1" to TenantId(b), "a2" to TenantId(a)),
                records.map { json.read(it.envelopeJson) }.map { it.who() to it.tenant },
            )
            assertEquals(2, harness.reader.read(topic, records[1].offset, OutboxHarness.LOG_LIMIT).size, "after offset")
            assertEquals(records.size.toLong(), harness.reader.backlog(topic, 0).count, "backlog on claim columns")

            assertEnvelopesBoundToTenant(harness, a, b)
            assertClaimColumnsOnly(harness)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a relay insert with an unbound, mismatched or missing tenant is refused`(image: String) {
        OutboxHarness(image).use { harness ->
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            harness.fixture.eventLogDataSource().connection.use { relay ->
                assertThrows(SQLException::class.java, { insert(relay, bound = null, row = a) }, "unbound")
                assertThrows(SQLException::class.java, { insert(relay, bound = a, row = b) }, "mismatched")
                assertThrows(SQLException::class.java, { insert(relay, bound = a, row = null) }, "no tenant")
                insert(relay, bound = a, row = a)
            }
            assertEquals(1, harness.count("select count(*) from $table where tenant_id = ?", a))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a runtime login reads only its subscribed topics and a non-subscriber's claim returns nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            val a = UUID.randomUUID()
            harness.fixture
                .eventLogDataSource()
                .connection
                .use { insert(it, bound = a, row = a) }
            val replayTable = "eventlog.gst_canonical_replay_reconciliation_v1"
            // recon_runtime subscribes only to its replay topics: no SELECT on the audit topic, and its claim is empty
            harness.fixture.roleDataSource("recon_runtime").connection.use { other ->
                assertThrows(SQLException::class.java) { count(other, "select count(*) from $table") }
                assertEquals(0, claimed(other, topic), "a non-subscriber's claim returns nothing")
                val own = count(other, "select count(*) from $replayTable")
                assertEquals(0, own, "its own replay topic: no tenant bound")
            }
            harness.fixture.eventLogDataSource().connection.use { app ->
                assertThrows(SQLException::class.java) { count(app, "select count(*) from $replayTable") }
                assertEquals(0, claimed(app, "gst.canonical.replay-reconciliation.v1"), "not its replay topic")
                assertEquals(1, claimed(app, topic), "its own topic")
            }
        }
    }

    @Test
    fun `the generated key is injective and every login a plain identifier`() {
        val sys = OutboxPostgresFixture.EVENTLOG_SYS

        fun topic(name: String) = EventLogSchema.Topic(name, setOf("a_runtime"), setOf("b_runtime"), false)
        assertThrows(IllegalArgumentException::class.java, {
            EventLogSchema.functionsDdl(listOf(topic("gst.a.b-c.v1"), topic("gst.a.b.c.v1")), sys, "purge")
        }, "two topics on one table name")
        assertThrows(IllegalArgumentException::class.java, {
            EventLogSchema.functionsDdl(listOf(topic("gst.a.b.v1"), topic("gst.a.b.v1")), sys, "purge")
        }, "a topic twice")
        assertThrows(IllegalArgumentException::class.java, {
            EventLogSchema.functionsDdl(listOf(topic("gst.a.b.v1")), "b_runtime", "purge")
        }, "the sys role as a subscriber")
        assertThrows(IllegalArgumentException::class.java, {
            EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), setOf("b; drop role x"), false)
        }, "a login that is no identifier")
        assertThrows(IllegalArgumentException::class.java, {
            EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), setOf("b_runtime"), false, keeper = "c_runtime")
        }, "a keeper that does not subscribe")
        assertThrows(IllegalArgumentException::class.java, {
            EventLogSchema.topicDdl(topic("gst.a.b.v1"), "a_runtime")
        }, "the sys role as a producer")
    }

    private fun assertEnvelopesBoundToTenant(
        harness: OutboxHarness,
        a: UUID,
        b: UUID,
    ) {
        harness.fixture.eventLogDataSource().connection.use { reader ->
            assertEquals(0, envelopes(reader, null), "no tenant bound: nothing")
            assertEquals(0, envelopes(reader, UUID.randomUUID()), "another tenant: nothing")
            assertEquals(2, envelopes(reader, a), "tenant a: its own two")
            assertEquals(1, envelopes(reader, b), "tenant b: its own one")
            assertEquals(1, envelopes(reader, TenantId.PLATFORM.value), "platform: its own one")
        }
    }

    private fun assertClaimColumnsOnly(harness: OutboxHarness) {
        harness.fixture.eventLogDataSource().connection.use { claim ->
            val all = claimed(claim, topic)
            assertEquals(4, all, "the subscriber claims every tenant's offsets through eventlog.claim")
            assertEquals(0, count(claim, "select count(*) from $table"), "but reads no row of its own without a tenant")
        }
    }

    private fun claimed(
        connection: Connection,
        topic: String,
    ): Int =
        connection.prepareStatement("select count(*) from eventlog.claim(?, 0, 100)").use {
            it.setString(1, topic)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun count(
        connection: Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                it.next()
                it.getInt(1)
            }
        }

    private fun envelopes(
        connection: Connection,
        tenant: UUID?,
    ): Int {
        connection.autoCommit = false
        try {
            if (tenant != null) bind(connection, tenant)
            return connection.createStatement().use { statement ->
                statement.executeQuery("select count(envelope) from $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        } finally {
            connection.rollback()
        }
    }

    private fun insert(
        connection: Connection,
        bound: UUID?,
        row: UUID?,
    ) {
        connection.autoCommit = false
        try {
            if (bound != null) bind(connection, bound)
            connection
                .prepareStatement(
                    "insert into $table (tenant_id, event_id, subject, envelope) values (?, 'e', 's', '{}')",
                ).use {
                    it.setObject(1, row)
                    it.executeUpdate()
                }
            connection.commit()
        } catch (failure: SQLException) {
            connection.rollback()
            throw failure
        }
    }

    private fun bind(
        connection: Connection,
        tenant: UUID,
    ) {
        connection.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
    }

    private fun audit(who: String) = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, """{"who":"$who"}""")

    private fun Envelope.who(): String = mapper.readTree(dataJson).get("who").asString()

    private fun ServiceKit.context(
        tenant: TenantId,
        key: String,
    ) = EventContext(
        tenant = tenant,
        entity = if (tenant.isPlatform) EntityRef.PLATFORM else EntityRef("entity-1"),
        docUuid = null,
        transactionDate = if (tenant.isPlatform) null else LocalDate.of(2026, 9, 30),
        aggregateKey = key,
        aggregateVersion = 1,
        correlation = CorrelationId("corr-$key"),
        causation = CausationId("cmd-$service"),
    )
}
