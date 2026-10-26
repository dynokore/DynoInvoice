package com.dynokore.gst.ingestion.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/**
 * INV-AC-1900 (INV-REQ-854): the query guard. Two tenants hold a row in every tenant table of the schema; a session
 * of tenant 1 runs, for each table, a query that has no `WHERE` on the tenant, and no row of tenant 2 comes back; a
 * session without a tenant gets nothing. The tables are read from the catalogue, so a tenant table added by a later
 * migration is queried too (ingestion-adapters holds no hand-written repository class yet; its queries are these).
 */
class QueryGuardTest {
    private val tenant1 = UUID.randomUUID()
    private val tenant2 = UUID.randomUUID()

    private fun superuser(statement: String) =
        IngestionDb.superuser().use { it.createStatement().use { s -> s.execute(statement) } }

    private fun seed(tenant: UUID) {
        val t = "'$tenant'"
        superuser(
            "insert into ingestion.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, " +
                "instance_id, correlation_id, causation_id, data) values ('$tenant', 'gst.ingestion.example.v1', " +
                "'Example', 'ingestion-svc', 's', now(), $t, 'E', 'i', 'c', 'c', '{}'::jsonb)",
        )
        superuser("insert into ingestion.outbox_sequence values ($t, 'k', 1)")
        superuser("insert into ingestion.inbox (consumer, event_id, tenant_id) values ('c', '$tenant', $t)")
        superuser("insert into ingestion.consumer_sequence values ('c', 's', 'sub', $t, 1)")
        superuser(
            "insert into ingestion.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw) " +
                "values ('c', 't', 1, $t, 'r', 'raw')",
        )
    }

    private fun tenantTables(): List<String> =
        IngestionDb.superuser().use { connection ->
            connection
                .createStatement()
                .use { statement ->
                    statement
                        .executeQuery(
                            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                                "join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' " +
                                "and not a.attisdropped where n.nspname = 'ingestion' and c.relkind in ('r', 'p') " +
                                "and has_table_privilege('ingestion_runtime', c.oid, 'select') order by 1",
                        ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                }
        }

    /** The tenants of the rows the unfiltered query of [table] returns in a transaction of the runtime login. */
    private fun tenantsSeen(
        table: String,
        session: UUID?,
    ): List<String> =
        IngestionDb.connect(IngestionDb.RUNTIME, IngestionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            if (session != null) SignedBindings.bind(connection, session)
            connection.rows("select tenant_id::text from ingestion.$table").also { connection.rollback() }
        }

    private fun Connection.rows(query: String): List<String> =
        createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }

    @Test
    fun `INV-AC-1900 an unfiltered query returns no row of another tenant and a session without tenant gets nothing`() {
        seed(tenant1)
        seed(tenant2)
        val tables = tenantTables()
        tables shouldContainAll listOf("outbox", "outbox_sequence", "inbox", "consumer_sequence", "event_dead_letter")
        tables.forEach { table ->
            tenantsSeen(table, tenant1).toSet() shouldBe setOf(tenant1.toString())
            tenantsSeen(table, tenant2).filter { it == tenant1.toString() }.shouldBeEmpty()
            tenantsSeen(table, null).shouldBeEmpty()
        }
    }
}
