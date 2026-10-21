package com.dynokore.gst.canonical.adapters

import gst.build.konsist.MigrationLint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection

/** The shape of `source_event` (INV-AC-7863); look-alike probes are scratch tables, never committed. */
class SourceEventSchemaTest {
    private val scratch = "probe_source_event"

    @AfterEach
    fun dropScratch() {
        CanonicalDb.superuser().use { connection ->
            connection.createStatement().use { it.execute("drop schema if exists $scratch cascade") }
        }
    }

    private fun findings(
        schema: String = "canonical",
        table: String = "source_event",
    ): List<String> = CanonicalDb.superuser().use { SourceEventShape.findings(it, schema, table) }

    private fun scratchTable(
        ddl: String,
        schema: String = scratch,
    ) = CanonicalDb.superuser().use { connection ->
        connection.createStatement().use {
            it.execute("create schema if not exists $schema")
            it.execute(ddl)
        }
    }

    private fun ingestionTables(connection: Connection): List<String> =
        connection.prepareStatement(INGESTION_TABLES).use { statement ->
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }

    @Test
    fun `INV-AC-7863 source_event has the five columns and the four-part unique key, ingestion unchanged`() {
        val before = CanonicalDb.superuser().use { ingestionTables(it) }
        findings().shouldBeEmpty()
        CanonicalDb.superuser().use { ingestionTables(it) } shouldBe before
        before.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-7863 probe the same table in a schema other than canonical fails`() {
        scratchTable(GOOD.replace("canonical.", "$scratch."))
        // the shape alone is right in the scratch schema; the test pins the schema canonical, where this name is absent
        findings(scratch).shouldBeEmpty()
        findings("canonical", "source_event_in_scratch_only").shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-7863 probe a constraint over three of the four columns fails`() {
        scratchTable(
            GOOD
                .replace(", source_revision)", ")")
                .replace("canonical.", "$scratch."),
        )
        findings(scratch).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-7863 probe doc_uuid nullable fails`() {
        scratchTable(GOOD.replace("doc_uuid uuid not null", "doc_uuid uuid").replace("canonical.", "$scratch."))
        findings(scratch).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-7863 probe tenant_id nullable fails`() {
        scratchTable(GOOD.replace("tenant_id uuid not null", "tenant_id uuid").replace("canonical.", "$scratch."))
        findings(scratch).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-7863 probe the first column named tenant instead of tenant_id fails the test and the migration lint`() {
        val lookAlike = GOOD.replace("tenant_id", "tenant").replace("canonical.", "$scratch.")
        scratchTable(lookAlike)
        findings(scratch).shouldNotBeEmpty()
        MigrationLint.lint("probe.sql", listOf(1 to lookAlike)).isNotEmpty() shouldBe true
    }

    private companion object {
        const val INGESTION_TABLES =
            "select table_name from information_schema.tables where table_schema = 'ingestion' order by 1"

        val GOOD =
            "create table canonical.source_event (tenant_id uuid not null, source_system text not null, " +
                "source_key text not null, source_revision bigint not null, doc_uuid uuid not null, " +
                "unique (tenant_id, source_system, source_key, source_revision))"
    }
}

/** The decisive assertions of INV-AC-7863 over `information_schema.columns` and `pg_get_constraintdef`. */
object SourceEventShape {
    private val COLUMNS = setOf("tenant_id", "source_system", "source_key", "source_revision", "doc_uuid")
    private const val UNIQUE = "UNIQUE (tenant_id, source_system, source_key, source_revision)"

    fun findings(
        connection: Connection,
        schema: String,
        table: String,
    ): List<String> {
        val columns = mutableMapOf<String, Pair<String, String>>()
        connection
            .prepareStatement(
                "select column_name, data_type, is_nullable from information_schema.columns " +
                    "where table_schema = ? and table_name = ?",
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.executeQuery().use { rows ->
                    while (rows.next()) columns[rows.getString(1)] = rows.getString(2) to rows.getString(3)
                }
            }
        val constraints = mutableListOf<String>()
        connection
            .prepareStatement(
                "select pg_get_constraintdef(c.oid) from pg_constraint c join pg_class t on t.oid = c.conrelid " +
                    "join pg_namespace n on n.oid = t.relnamespace where n.nspname = ? and t.relname = ? " +
                    "and c.contype = 'u'",
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.executeQuery().use { rows -> while (rows.next()) constraints += rows.getString(1) }
            }
        return buildList {
            if (columns.keys != COLUMNS) add("columns ${columns.keys} differ from $COLUMNS")
            for (uuid in listOf("tenant_id", "doc_uuid")) {
                if (columns[uuid] != ("uuid" to "NO")) add("$uuid is ${columns[uuid]}, not uuid not null")
            }
            if (constraints != listOf(UNIQUE)) add("unique constraints $constraints differ from $UNIQUE")
        }
    }
}
