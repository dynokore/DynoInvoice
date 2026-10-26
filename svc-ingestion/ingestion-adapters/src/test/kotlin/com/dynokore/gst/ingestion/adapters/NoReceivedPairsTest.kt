package com.dynokore.gst.ingestion.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/**
 * INV-REQ-806, INV-REQ-1191 and INV-AC-2177: the schema `ingestion` holds no record of received idempotency pairs;
 * the recording and de-duplication decision is canonical-svc's. No table of the schema has a column that holds the
 * `sourceRevision` of a pair, under any table name and in snake or camel case.
 */
class NoReceivedPairsTest {
    private fun findings(columns: List<Pair<String, String>>): List<String> =
        columns
            .filter { (_, column) -> column.replace("_", "").lowercase() == "sourcerevision" }
            .map { (table, column) -> "$table.$column holds a source revision" }

    private fun columnsOfSchema(): List<Pair<String, String>> =
        IngestionDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select table_name, column_name from information_schema.columns " +
                            "where table_schema = 'ingestion' and table_name <> 'flyway_schema_history'",
                    ).use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toList()
                    }
            }
        }

    @Test
    fun `no table of the schema holds a source revision column`() {
        val columns = columnsOfSchema()
        columns.shouldNotBeEmpty()
        findings(columns).shouldBeEmpty()
    }

    @Test
    fun `a table of another name holding a source revision column is a finding`() {
        findings(listOf("received_items" to "id", "received_items" to "source_revision")).shouldNotBeEmpty()
        findings(listOf("anything" to "sourceRevision")).shouldNotBeEmpty()
        findings(listOf("anything" to "SOURCE_REVISION")).shouldNotBeEmpty()
    }

    @Test
    fun `a column of another meaning is not a finding`() {
        findings(listOf("outbox" to "source", "outbox" to "subject")).shouldBeEmpty()
    }
}
