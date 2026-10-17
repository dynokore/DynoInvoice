package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.APP
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.SYS
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

/**
 * INV-T-5306 (ruling INV-Q-6028): the generated clear function by column takes its filter as text and converts it
 * to the column's type by the server's input conversion, as an untyped literal was converted before. A filter the
 * column type reads as equal deletes the row (upper-case uuid, a blank around an integer, an ISO timestamp, numeric
 * 1.0 against 1.00, boolean `yes`); an invalid value fails with the type's SQLSTATE (22P02, 23514 for a domain) and
 * deletes nothing. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadModelClearTypesIT {
    private val tenant = UUID.randomUUID()
    private val fixtures = mutableMapOf<String, OutboxPostgresFixture>()

    @AfterAll
    fun close() = fixtures.values.forEach { it.close() }

    private data class Case(
        val name: String,
        val columnType: String,
        val stored: String,
        val filter: String,
        val deleted: Long?,
        val sqlState: String? = null,
    )

    private val cases =
        listOf(
            Case("text", "text", "abc", "abc", 1),
            Case(
                "uuid upper-case",
                "uuid",
                "0a1b2c3d-0000-4000-8000-00000000abcd",
                "0A1B2C3D-0000-4000-8000-00000000ABCD",
                1,
            ),
            Case("integer with blanks", "integer", "42", " 42 ", 1),
            Case("integer invalid", "integer", "42", "abc", null, "22P02"),
            Case("timestamptz ISO", "timestamptz", "2026-01-01 00:00:00+00", "2026-01-01T00:00:00Z", 1),
            Case("numeric 1.0 against 1.00", "numeric(10,2)", "1.00", "1.0", 1),
            Case("boolean yes", "boolean", "true", "yes", 1),
            Case("enum", "$SCHEMA.rm_mood", "happy", "happy", 1),
            Case("enum invalid label", "$SCHEMA.rm_mood", "happy", "angry", null, "22P02"),
            Case("domain", "$SCHEMA.rm_positive", "5", "5", 1),
            Case("domain violation", "$SCHEMA.rm_positive", "5", "-1", null, "23514"),
        )

    private fun table(case: Case) = "rm_" + case.name.replace(Regex("[^a-z]+"), "_").trim('_')

    private fun fixture(image: String) =
        fixtures.getOrPut(image) {
            val tables =
                cases.joinToString("\n") {
                    val t = table(it)
                    """
                    create table $SCHEMA.$t (k ${it.columnType} not null, tenant_id uuid not null);
                    ${TenantRlsMigration.ddl(SCHEMA, t, policyName = "${t}_tenant_isolation")}
                    grant select, insert, update, delete on $SCHEMA.$t to $APP;
                    ${OutboxSchema.readModelClearFunction(SCHEMA, t, APP, SYS, "k")}
                    """.trimIndent()
                }
            OutboxPostgresFixture(image).also { f ->
                f.createService(
                    SCHEMA,
                    """
                    create type $SCHEMA.rm_mood as enum ('sad', 'happy');
                    create domain $SCHEMA.rm_positive as integer check (value > 0);
                    $tables
                    """.trimIndent(),
                )
            }
        }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the clear function converts its text filter to the column type and fails as the type does`(image: String) {
        val f = fixture(image)
        assertEquals(emptyList<String>(), cases.mapNotNull { mismatch(f, it) })
    }

    /** What [case] got wrong, or null: the call's outcome and the rows left in its table. */
    private fun mismatch(
        f: OutboxPostgresFixture,
        case: Case,
    ): String? {
        val t = table(case)
        seed(f, t, case)
        val outcome = runCatching { clear(f, t, case.filter) }
        val rows = rowCount(f, t)
        val ok =
            if (case.deleted != null) {
                outcome.getOrNull() == case.deleted && rows == 0L
            } else {
                (outcome.exceptionOrNull() as? SQLException)?.sqlState == case.sqlState && rows == 1L
            }
        return if (ok) null else "${case.name}: $outcome, rows left $rows"
    }

    private fun seed(
        f: OutboxPostgresFixture,
        table: String,
        case: Case,
    ) {
        f.adminDataSource().connection.use { c ->
            c.createStatement().use { it.execute("delete from $SCHEMA.$table") }
            c.prepareStatement("insert into $SCHEMA.$table (k, tenant_id) values (?::${case.columnType}, ?)").use {
                it.setString(1, case.stored)
                it.setObject(2, tenant)
                it.executeUpdate()
            }
        }
    }

    private fun rowCount(
        f: OutboxPostgresFixture,
        table: String,
    ): Long =
        f.adminDataSource().connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from $SCHEMA.$table").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun clear(
        f: OutboxPostgresFixture,
        table: String,
        filter: String,
    ): Long =
        f.runtimeDataSource().connection.use { c ->
            c.autoCommit = false
            try {
                c
                    .prepareStatement("select $SCHEMA.${table}_full_sync_clear(?)")
                    .use {
                        it.setString(1, filter)
                        it.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }.also { c.commit() }
            } catch (failure: SQLException) {
                c.rollback()
                throw failure
            }
        }

    private companion object {
        const val SCHEMA = "payload"
    }
}
