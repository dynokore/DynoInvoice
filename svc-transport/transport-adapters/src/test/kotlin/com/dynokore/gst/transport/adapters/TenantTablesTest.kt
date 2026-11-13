package com.dynokore.gst.transport.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * CS-19 at the database: every table of the schema is tenant data under forced RLS, or declared platform-scoped.
 * Flyway's own history table is not a table of the service.
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
                !hasTenant && comment == "platform-scoped: no tenant data" -> null
                !hasTenant -> "$name: no tenant_id and not platform-scoped"
                tenantNotNull && rowSecurity && forced && signedPolicy -> null
                else -> "$name: tenant_id NOT NULL, ENABLE and FORCE ROW LEVEL SECURITY and a bound_tenant() policy"
            }
    }

    private fun tables(): List<TableFacts> = TransportDb.superuser().use { tables(it) }

    private fun tables(connection: java.sql.Connection): List<TableFacts> =
        connection.createStatement().use { statement ->
            statement.executeQuery(INSPECT).use { rows ->
                generateSequence { if (rows.next()) TableFacts(rows) else null }.toList()
            }
        }

    /** Creates the scratch [ddl] in one transaction, reads the findings of [table] as the gate does, and rolls back. */
    private fun findingOf(
        table: String,
        ddl: String,
    ): String? =
        TransportDb.superuser().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(ddl) }
                tables(connection).single { it.name == table }.finding()
            } finally {
                connection.rollback()
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
                "ap_connection",
                "entity_adapter",
                "submission_attempt",
                "attempt_egress",
                "outbox",
            ) -
                tables().map { it.name }.toSet()
        ).shouldBeEmpty()
    }

    @Test
    fun `the two credential tables exist and the plural look-alike names do not`() {
        val names = tables().map { it.name }
        (setOf("ap_credential_state", "ap_credential_rotation") - names.toSet()).shouldBeEmpty()
        // look-alike: the plural name is not a table of the schema
        (names.contains("ap_credential_states") || names.contains("ap_credential_rotations")) shouldBe false
    }

    @Test
    fun `a credential table declared without tenant_id is a finding`() {
        findingOf(
            "ap_credential_states",
            "create table transport.ap_credential_states (adapter_id text primary key, current_ref text not null)",
        ) shouldBe "ap_credential_states: no tenant_id and not platform-scoped"
    }

    @Test
    fun `a credential table without force row level security is a finding`() {
        findingOf(
            "ap_credential_states",
            GOOD_DDL +
                "alter table transport.ap_credential_states enable row level security;" +
                POLICY.format("(select transport.bound_tenant())", "(select transport.bound_tenant())"),
        ) shouldBe EXPECTED
    }

    @Test
    fun `a credential table whose policy compares another function is a finding`() {
        findingOf(
            "ap_credential_states",
            GOOD_DDL + FORCED + POLICY.format("(select gen_random_uuid())", "(select gen_random_uuid())"),
        ) shouldBe EXPECTED
    }

    @Test
    fun `a credential table whose policy has only using is a finding`() {
        findingOf(
            "ap_credential_states",
            GOOD_DDL + FORCED +
                "create policy p on transport.ap_credential_states using (tenant_id = (select transport.bound_tenant()));",
        ) shouldBe EXPECTED
    }

    @Test
    fun `a credential table whose policy checks only with check is a finding`() {
        findingOf(
            "ap_credential_states",
            GOOD_DDL + FORCED +
                "create policy p on transport.ap_credential_states " +
                "with check (tenant_id = (select transport.bound_tenant()));",
        ) shouldBe EXPECTED
    }

    @Test
    fun `a credential table with a nullable tenant_id is a finding`() {
        findingOf(
            "ap_credential_states",
            "create table transport.ap_credential_states (tenant_id uuid, adapter_id text, primary key (adapter_id));" +
                FORCED +
                POLICY.format("(select transport.bound_tenant())", "(select transport.bound_tenant())"),
        ) shouldBe EXPECTED
    }

    @Test
    fun `the look-alike table with all four properties passes, so the probes above fail only for their defect`() {
        findingOf(
            "ap_credential_states",
            GOOD_DDL + FORCED + POLICY.format("(select transport.bound_tenant())", "(select transport.bound_tenant())"),
        ) shouldBe null
    }

    private companion object {
        const val EXPECTED =
            "ap_credential_states: tenant_id NOT NULL, ENABLE and FORCE ROW LEVEL SECURITY and a bound_tenant() policy"
        const val GOOD_DDL =
            "create table transport.ap_credential_states (tenant_id uuid not null, adapter_id text not null, " +
                "primary key (tenant_id, adapter_id));"
        const val FORCED =
            "alter table transport.ap_credential_states enable row level security;" +
                "alter table transport.ap_credential_states force row level security;"
        const val POLICY =
            "create policy p on transport.ap_credential_states using (tenant_id = %s) with check (tenant_id = %s);"
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
                "where n.nspname = 'transport' and c.relkind in ('r', 'p') " +
                "and c.relname <> 'flyway_schema_history' order by 1"
    }

    private fun runtime(block: (java.sql.Connection) -> Unit) =
        TransportDb.connect(TransportDb.RUNTIME, TransportDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            block(connection)
        }

    private fun insertAttempt(
        tenant: UUID,
        signature: String = SignedBindings.signed(tenant),
    ) = runtime { connection ->
        SignedBindings.bind(connection, tenant, signature = signature)
        connection
            .prepareStatement(
                "insert into transport.submission_attempt " +
                    "(id, tenant_id, entity_ref, doc_uuid, version, adapter_id, started_at) " +
                    "values (?, ?, 'E-001', gen_random_uuid(), 1, 'primary', now())",
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
                "select count(*) from transport.submission_attempt where tenant_id = ?",
            ).apply { setObject(1, tenant) },
        )

    private fun java.sql.Connection.rowsNotOf(tenant: UUID): Int =
        single(
            prepareStatement(
                "select count(*) from transport.submission_attempt where tenant_id <> ?",
            ).apply { setObject(1, tenant) },
        )

    private fun java.sql.Connection.allRows(): Int =
        single(prepareStatement("select count(*) from transport.submission_attempt"))

    @Test
    fun `a session of tenant A reads no row of tenant B`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertAttempt(a)
        insertAttempt(b)
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
        insertAttempt(tenant)
        val forged = SignedBindings.signed(UUID.randomUUID())
        runtime { connection ->
            SignedBindings.bind(connection, tenant, signature = forged)
            connection.allRows() shouldBe 0
            connection.rollback()
        }
        shouldThrow<SQLException> { insertAttempt(tenant, signature = forged) }.sqlState shouldBe "42501"
    }
}
