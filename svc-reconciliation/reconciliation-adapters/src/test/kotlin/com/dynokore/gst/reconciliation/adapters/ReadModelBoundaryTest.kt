package com.dynokore.gst.reconciliation.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * The read-model boundary at the database (INV-REQ-1206): the role `reconciliation` reads nothing of `canonical` or
 * `submission` and holds USAGE on its own schema only. Each test builds its scratch schemas in a transaction on the
 * superuser connection and rolls back, so no other test sees them. Names are compared by equality: `canonical_x` is
 * a different schema from `canonical`.
 */
class ReadModelBoundaryTest {
    private val role = "reconciliation"
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
    private fun Connection.usage(): List<Boolean> =
        schemas.flatMap { booleans("select has_schema_privilege('$role', '$it', 'USAGE')") }

    private fun Connection.select(): List<Boolean> =
        scratchTables.flatMap { booleans("select has_table_privilege('$role', '$it', 'SELECT')") }

    private fun Connection.findings(): List<String> =
        buildList {
            if (usage() != listOf(true, false, false, false)) add("USAGE ${usage()}")
            if (select().any { it }) add("SELECT ${select()}")
        }

    /** The SQLSTATE of a statement run as the role, or null when it succeeded; the transaction is left usable. */
    private fun Connection.asRole(sql: String): String? {
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

    @Test
    fun `INV-AC-2189 the role reconciliation is refused canonical and submission and works in its own schema`() =
        scratch { connection ->
            connection.asRole("select * from canonical.t") shouldBe INSUFFICIENT_PRIVILEGE
            connection.asRole("select * from submission.t") shouldBe INSUFFICIENT_PRIVILEGE
            connection.asRole("select * from canonical_x.t") shouldBe INSUFFICIENT_PRIVILEGE
            connection.asRole(
                "create table reconciliation.scratch_model (id int); " +
                    "insert into reconciliation.scratch_model values (1); select * from reconciliation.scratch_model",
            ) shouldBe null
        }

    @Test
    fun `INV-AC-9536 the role reconciliation has USAGE on schema reconciliation only and SELECT on no scratch table`() =
        scratch { connection ->
            connection.usage() shouldBe listOf(true, false, false, false)
            connection.select() shouldBe listOf(false, false, false)
            connection.findings().shouldBeEmpty()
        }

    @Test
    fun `INV-AC-2189 a grant of USAGE and SELECT on canonical makes the check fail`() =
        scratch(
            listOf(
                "grant usage on schema canonical to reconciliation",
                "grant select on canonical.t to reconciliation",
            ),
        ) {
            it.findings().shouldNotBeEmpty()
            it.asRole("select * from canonical.t") shouldBe null
        }

    @Test
    fun `INV-AC-9536 a grant on the look-alike canonical_x fails the check and canonical stays closed`() =
        scratch(listOf("grant usage on schema canonical_x to reconciliation")) {
            it.findings().shouldNotBeEmpty()
            it.usage() shouldBe listOf(true, false, false, true)
        }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
