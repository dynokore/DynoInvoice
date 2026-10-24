package com.dynokore.gst.notification.adapters

import com.dynokore.platform.outboxandqueue.OutboxSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * CS-19 at the database: every table of the schema is tenant data under forced RLS, or declared platform-scoped.
 * Flyway's own history table and `binding_key` (installed by the tenancy kernel's BindingKeyMigration, not by a
 * migration of the service; not in OutboxSchema.platformScopedTables) are not tables of the service.
 */
class TenantTablesTest {
    private class TableFacts(
        rows: java.sql.ResultSet,
    ) {
        val name: String = rows.getString(1)
        private val rowSecurity = rows.getBoolean(2)
        private val forced = rows.getBoolean(3)
        private val tenantNotNull = rows.getBoolean(4)
        private val hasTenant = rows.getBoolean(5)
        private val comment = rows.getString(6)
        private val signedPolicy = rows.getBoolean(7)

        fun finding(): String? =
            when {
                !hasTenant && exempt(name, comment) -> null
                !hasTenant -> "$name: no tenant_id and not platform-scoped"
                else -> tenantFinding()
            }

        private fun tenantFinding(): String? =
            when {
                tenantNotNull && rowSecurity && forced && signedPolicy -> null
                else -> "$name: tenant_id NOT NULL, ENABLE and FORCE ROW LEVEL SECURITY and a bound_tenant() policy"
            }
    }

    private fun tables(): List<TableFacts> =
        NotificationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(INSPECT).use { rows ->
                    generateSequence { if (rows.next()) TableFacts(rows) else null }.toList()
                }
            }
        }

    @Test
    fun `every table has a non-null tenant column with forced row level security and the signed binding policy`() {
        tables().mapNotNull { it.finding() }.shouldBeEmpty()
    }

    @Test
    fun `the tables of the first migration exist`() {
        (
            setOf(
                "webhook_subscription",
                "webhook_subscription_scope",
                "outbox",
                "work_queue",
                "lane_rate_window",
            ) -
                tables().map { it.name }.toSet()
        ).shouldBeEmpty()
        tables().map { it.name }.filter { it == "queue_item" }.shouldBeEmpty() // one queue table only (INV-Q-9743 A)
    }

    @Test
    fun `the exemption names equal the platform-scoped tables of the outbox library`() {
        PLATFORM_SCOPED.toSet() shouldBe
            OutboxSchema.platformScopedTables("notification").map { it.removePrefix("notification.") }.toSet()
    }

    @Test
    fun `the exemption needs the exact name and the comment`() {
        exempt("lane_rate_window", COMMENT) shouldBe true
        PLATFORM_SCOPED.forEach { exempt(it, COMMENT) shouldBe true }
        exempt("probe_free", COMMENT) shouldBe false // the comment alone does not exempt
        exempt("consumer_offset_x", COMMENT) shouldBe false // look-alike name
        exempt("Consumer_Offset", COMMENT) shouldBe false
        exempt("consumer_offset", "") shouldBe false // the name alone does not exempt
        exempt("consumer_offset", "platform-scoped: no tenant data ") shouldBe false
    }

    private companion object {
        const val COMMENT = "platform-scoped: no tenant data"
        val PLATFORM_SCOPED =
            listOf(
                "consumer_offset",
                "consumer_replay",
                "consumer_replay_marker",
                "consumer_source_seen",
                "lane_rate_window",
            )

        fun exempt(
            name: String,
            comment: String?,
        ): Boolean = name in PLATFORM_SCOPED && comment == COMMENT

        const val INSPECT =
            "select c.relname, c.relrowsecurity, c.relforcerowsecurity, " +
                "exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id' " +
                "and a.attnotnull and not a.attisdropped), " +
                "exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id'), " +
                "coalesce(obj_description(c.oid, 'pg_class'), ''), " +
                "exists (select 1 from pg_policy p where p.polrelid = c.oid " +
                "and pg_get_expr(p.polqual, p.polrelid) like '%bound_tenant()%' " +
                "and pg_get_expr(p.polwithcheck, p.polrelid) like '%bound_tenant()%') " +
                "from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'notification' and c.relkind in ('r', 'p') " +
                "and c.relname not in ('flyway_schema_history', 'binding_key') order by 1"
    }

    private fun runtime(block: (java.sql.Connection) -> Unit) =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            block(connection)
        }

    private fun insertSubscription(
        tenant: UUID,
        signature: String = SignedBindings.signed(tenant),
    ) = runtime { connection ->
        SignedBindings.bind(connection, tenant, signature = signature)
        connection
            .prepareStatement(
                "insert into notification.webhook_subscription (id, tenant_id, event_type, endpoint_ref) " +
                    "values (?, ?, 'document.rejected', 'secret://endpoint-1')",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant)
                it.executeUpdate()
            }
        connection.commit()
    }

    private fun java.sql.Connection.single(statement: java.sql.PreparedStatement): Int =
        statement.use { prepared ->
            prepared.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun java.sql.Connection.rowsOf(tenant: UUID): Int =
        single(
            prepareStatement(
                "select count(*) from notification.webhook_subscription where tenant_id = ?",
            ).apply { setObject(1, tenant) },
        )

    private fun java.sql.Connection.rowsNotOf(tenant: UUID): Int =
        single(
            prepareStatement(
                "select count(*) from notification.webhook_subscription where tenant_id <> ?",
            ).apply { setObject(1, tenant) },
        )

    private fun java.sql.Connection.allRows(): Int =
        single(prepareStatement("select count(*) from notification.webhook_subscription"))

    @Test
    fun `a session of tenant A reads no row of tenant B`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertSubscription(a)
        insertSubscription(b)
        runtime { connection ->
            SignedBindings.bind(connection, a)
            connection.rowsOf(b) shouldBe 0
            connection.rowsNotOf(a) shouldBe 0
            connection.rowsOf(a) shouldBe 1
        }
    }

    @Test
    fun `a forged binding reads no row and a write under it is refused`() {
        val tenant = UUID.randomUUID()
        insertSubscription(tenant)
        val forged = SignedBindings.signed(UUID.randomUUID())
        runtime { connection ->
            SignedBindings.bind(connection, tenant, signature = forged)
            connection.allRows() shouldBe 0
            connection.rollback()
        }
        shouldThrow<SQLException> { insertSubscription(tenant, signature = forged) }.sqlState shouldBe "42501"
    }
}
