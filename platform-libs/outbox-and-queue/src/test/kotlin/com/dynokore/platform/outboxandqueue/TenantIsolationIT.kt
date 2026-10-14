package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

/**
 * Row-level security on every tenant table of the module (tenancy-kernel's `TenantRlsMigration`) and on
 * the dead-letter table, which keeps whole rejected envelopes (content): proved on PostgreSQL 17 and 18.
 */
@Tag("integration")
class TenantIsolationIT {
    private val tables = listOf("outbox", "outbox_sequence", "inbox", "consumer_sequence", "event_dead_letter")

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `RLS is enabled and forced on every table and another tenant sees no rows in any of them`(image: String) {
        OutboxHarness(image).use { harness ->
            val owner = UUID.randomUUID()
            seed(harness, owner)

            tables.forEach { table ->
                val flags =
                    harness.flags("submission", table)
                assertEquals(listOf(true, true), flags, "RLS flags of $table")
            }
            val kit = harness.submission
            tables.filter { it != "event_dead_letter" }.forEach { table ->
                assertTrue(kit.inTx(owner) { visible(kit, table) } > 0, "owner sees $table")
                assertEquals(0, kit.inTx(UUID.randomUUID()) { visible(kit, table) }, "other tenant sees $table")
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `dead letters carry the tenant, tenants read only their own raw, the runtime login none directly`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val tenant = UUID.randomUUID()
            val topic = "eventlog.gst_payload_payload_ready_v1"
            harness.fixture.runAsAdmin(
                "insert into $topic (tenant_id, event_id, subject, envelope) values " +
                    "('$tenant', 'a', 's', '{\"specversion\":\"1.0\",\"tenantid\":\"$tenant\"}'), " +
                    "('${UUID.randomUUID()}', 'b', 's', '{\"specversion\":\"1.0\"}')",
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
            assertEquals(2, consumer.pollOnce().deadLettered)

            val deadLetters = "select count(*) from submission.event_dead_letter"
            assertEquals(1, harness.count("$deadLetters where tenant_id = ?", tenant))
            // a record that names no tenant is stored under the platform tenant (CS-19: tenant_id NOT NULL)
            assertEquals(1, harness.count("$deadLetters where tenant_id = ?", TenantId.PLATFORM.value))
            assertEquals(1, kit.inTx(tenant) { visible(kit, "event_dead_letter") }, "a tenant reads its own letter")
            val other = UUID.randomUUID()
            assertEquals(0, kit.inTx(other) { visible(kit, "event_dead_letter") }, "a cross-tenant read is refused")
            assertEquals(0, kit.inTx(other) { rawCount(kit) }, "another tenant reads no raw")
            assertEquals(1, kit.inTx(tenant) { rawCount(kit) }, "a tenant reads its own letter only")
            runtimeReadsMetadataOnly(harness)
        }
    }

    /**
     * The runtime login reads no letter directly outside a tenant transaction (forced RLS, no tenant bound), and the
     * sys-owned triage view gives every letter's metadata and never `raw` (INV-Q-3953).
     */
    private fun runtimeReadsMetadataOnly(harness: OutboxHarness) {
        harness.fixture.runtimeDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                val table = "submission.event_dead_letter"
                val view = "submission.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW}"
                statement.executeQuery("select count(*) from $table").use {
                    it.next()
                    assertEquals(0, it.getInt(1), "no letter directly")
                }
                statement.executeQuery("select count(*) from $view").use {
                    it.next()
                    assertEquals(2, it.getInt(1))
                }
                statement.executeQuery("select id, reason from $view").close()
                listOf("select raw from $view", "select id from $view where raw like '%'")
                    .forEach { sql -> assertThrows(SQLException::class.java, { statement.executeQuery(sql) }, sql) }
            }
        }
    }

    private fun seed(
        harness: OutboxHarness,
        tenant: UUID,
    ) {
        val doc = UUID.randomUUID()
        val payload = harness.payload
        payload.inTx(tenant) {
            payload.writer.append(
                listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"version":1}""")),
                payload.docContext(tenant, doc),
            )
        }
        harness.drain(payload.relay)
        val kit = harness.submission
        kit.inTx(tenant) {
            kit.writer.append(
                listOf(OutboundEvent(TestCatalogue.SUBMISSION_DESPATCHED, "{}")),
                kit.docContext(tenant, doc),
            )
        }
        harness
            .consumer(
                "submission-svc",
                "gst.payload.payload-ready.v1",
                "submission",
                kit.boundary,
                SubmissionFixtureHandler(kit.boundary, TransportStub()),
            ).pollOnce()
        harness.fixture.runAsAdmin(
            "insert into submission.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw) " +
                "values ('c', 't', 1, '$tenant', 'r', '{}')",
        )
    }

    private fun rawCount(kit: ServiceKit): Int =
        kit.boundary.require().createStatement().use { statement ->
            statement.executeQuery("select count(raw) from submission.event_dead_letter").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun visible(
        kit: ServiceKit,
        table: String,
    ): Int =
        kit.boundary.require().createStatement().use { statement ->
            statement.executeQuery("select count(*) from submission.$table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
}
