package com.dynokore.gst.reconciliation.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * The read-model boundary at the database (INV-REQ-1206): each role of the family INV-AC-2175
 * (`reconciliation_migrate`, `reconciliation_runtime`, `reconciliation_sys`) reads nothing of `canonical` or
 * `submission` and holds USAGE on its own schema only; no role named `reconciliation` exists. Each test builds its
 * scratch schemas in a transaction on the
 * superuser connection and rolls back, so no other test sees them. Names are compared by equality: `canonical_x` is
 * a different schema from `canonical`.
 */
class ReadModelBoundaryTest {
    private val roles = listOf("reconciliation_migrate", "reconciliation_runtime", "reconciliation_sys")
    private val schemas = listOf("reconciliation", "canonical", "submission", "canonical_x")
    private val scratchTables = listOf("canonical.t", "submission.t", "canonical_x.t")

    private fun scratch(
        extra: List<String> = emptyList(),
        block: (Connection) -> Unit,
    ) = ReconciliationDb.superuser().use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                listOf("canonical", "submission", "canonical_x").forEach {
                    statement.execute("create schema $it")
                    statement.execute("create table $it.t (id int)")
                    statement.execute("insert into $it.t values (1)")
                }
                extra.forEach(statement::execute)
            }
            block(connection)
        } finally {
            connection.rollback()
        }
    }

    private fun Connection.booleans(sql: String): List<Boolean> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                generateSequence { if (rows.next()) rows.getBoolean(1) else null }.toList()
            }
        }

    /** The USAGE results in the order of [schemas] and the SELECT results in the order of [scratchTables]. */
    private fun Connection.usage(role: String): List<Boolean> =
        schemas.flatMap { booleans("select has_schema_privilege('$role', '$it', 'USAGE')") }

    private fun Connection.select(role: String): List<Boolean> =
        scratchTables.flatMap { booleans("select has_table_privilege('$role', '$it', 'SELECT')") }

    private fun Connection.findings(role: String): List<String> =
        buildList {
            if (usage(role) != listOf(true, false, false, false)) add("$role USAGE ${usage(role)}")
            if (select(role).any { it }) add("$role SELECT ${select(role)}")
        }

    private fun Connection.roleNames(): Set<String> =
        createStatement().use { statement ->
            statement
                .executeQuery(
                    "select rolname from pg_roles where rolname in " +
                        "('reconciliation_migrate', 'reconciliation_runtime', 'reconciliation_sys', 'reconciliation')",
                ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() }
        }

    /** The SQLSTATE of a statement run as the role, or null when it succeeded; the transaction is left usable. */
    private fun Connection.asRole(
        role: String,
        sql: String,
    ): String? {
        val savepoint = setSavepoint()
        createStatement().use { it.execute("set local role $role") }
        return try {
            createStatement().use { it.execute(sql) }
            null
        } catch (e: SQLException) {
            e.sqlState
        } finally {
            rollback(savepoint)
        }
    }

    private fun Connection.rowCount(
        role: String,
        table: String,
    ): Int {
        val savepoint = setSavepoint()
        createStatement().use { it.execute("set local role $role") }
        return try {
            createStatement().use { s ->
                s.executeQuery("select count(*) from $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        } finally {
            rollback(savepoint)
        }
    }

    @Test
    fun `INV-AC-2189 reconciliation_runtime is refused canonical and submission and reads its own schema`() =
        scratch { connection ->
            val runtime = "reconciliation_runtime"
            connection.asRole(runtime, "select * from canonical.t") shouldBe INSUFFICIENT_PRIVILEGE
            connection.asRole(runtime, "select * from submission.t") shouldBe INSUFFICIENT_PRIVILEGE
            connection.asRole(runtime, "select * from canonical_x.t") shouldBe INSUFFICIENT_PRIVILEGE
            connection.asRole(runtime, "select * from reconciliation.outbox") shouldBe null
            connection.rowCount(runtime, "reconciliation.outbox") shouldBe 0
        }

    @Test
    fun `INV-AC-2189 reconciliation_migrate and reconciliation_sys are refused canonical and submission`() =
        scratch { connection ->
            listOf("reconciliation_migrate", "reconciliation_sys").forEach { role ->
                connection.asRole(role, "select * from canonical.t") shouldBe INSUFFICIENT_PRIVILEGE
                connection.asRole(role, "select * from submission.t") shouldBe INSUFFICIENT_PRIVILEGE
                connection.asRole(role, "select * from canonical_x.t") shouldBe INSUFFICIENT_PRIVILEGE
            }
            connection.asRole(
                "reconciliation_migrate",
                "create table reconciliation.scratch_model (id int); " +
                    "insert into reconciliation.scratch_model values (1); select * from reconciliation.scratch_model",
            ) shouldBe null
        }

    @Test
    fun `INV-AC-9536 the three roles exist, no role reconciliation exists`() =
        scratch { connection ->
            connection.roleNames() shouldBe roles.toSet()
        }

    @Test
    fun `INV-AC-9536 each role has USAGE on schema reconciliation only and SELECT on no scratch table`() =
        scratch { connection ->
            roles.forEach { role ->
                connection.usage(role) shouldBe listOf(true, false, false, false)
                connection.select(role) shouldBe listOf(false, false, false)
                connection.findings(role).shouldBeEmpty()
            }
        }

    @Test
    fun `INV-AC-9536 a role reconciliation makes the role check fail`() =
        scratch(listOf("create role reconciliation")) { connection ->
            (connection.roleNames() == roles.toSet()) shouldBe false
        }

    @Test
    fun `INV-AC-2189 a grant on canonical to any role of the family makes the checks fail`() =
        roles.forEach { role ->
            scratch(listOf("grant usage on schema canonical to $role", "grant select on canonical.t to $role")) {
                it.findings(role).shouldNotBeEmpty()
                if (role == "reconciliation_runtime") it.asRole(role, "select * from canonical.t") shouldBe null
            }
        }

    @Test
    fun `INV-AC-9536 a grant on the look-alike canonical_x fails the check and canonical stays closed`() =
        scratch(listOf("grant usage on schema canonical_x to reconciliation_runtime")) {
            it.findings("reconciliation_runtime").shouldNotBeEmpty()
            it.usage("reconciliation_runtime") shouldBe listOf(true, false, false, true)
            it.asRole("reconciliation_runtime", "select * from canonical.t") shouldBe INSUFFICIENT_PRIVILEGE
        }

    @Test
    fun `INV-AC-9536 grants to the look-alike role reconciliation_runtime_x leave the checks passing`() =
        scratch(
            listOf(
                "create role reconciliation_runtime_x",
                "grant usage on schema canonical to reconciliation_runtime_x",
                "grant select on canonical.t to reconciliation_runtime_x",
            ),
        ) {
            roles.forEach { role -> it.findings(role).shouldBeEmpty() }
            it.asRole("reconciliation_runtime", "select * from canonical.t") shouldBe INSUFFICIENT_PRIVILEGE
        }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
