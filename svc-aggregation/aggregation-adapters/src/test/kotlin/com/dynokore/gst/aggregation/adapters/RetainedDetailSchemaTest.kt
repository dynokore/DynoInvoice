package com.dynokore.gst.aggregation.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The retained underlying-transaction list holds a claimant reference and no claimant name (INV-REQ-884, FR-AE.73):
 * judged from the catalogue of the `aggregation` schema. A table retains underlying transactions when it has a
 * `transaction_ref` column (deny by default: a new table with that column is checked without being listed).
 */
class RetainedDetailSchemaTest {
    /** One (table, column) pair of the catalogue. */
    private data class Column(
        val table: String,
        val name: String,
    )

    private fun catalogue(): List<Column> =
        AggregationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select table_name, column_name from information_schema.columns where table_schema = 'aggregation'",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        generateSequence {
                            if (rows.next()) {
                                Column(
                                    rows.getString(1),
                                    rows.getString(2),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    /** The columns of the retaining tables whose name contains `name` (case-insensitive). */
    private fun nameColumns(columns: List<Column>): List<String> {
        val retaining = columns.filter { it.name == "transaction_ref" }.map { it.table }.toSet()
        return columns
            .filter { it.table in retaining && it.name.contains("name", ignoreCase = true) }
            .map { "${it.table}.${it.name}" }
    }

    @Test
    fun `INV-AC-22472 no column of a table that retains underlying transactions is named like a name`() {
        val columns = catalogue()
        (
            columns
                .filter { it.name == "transaction_ref" }
                .map { it.table }
                .toSet()
                .isNotEmpty()
        ) shouldBe true
        nameColumns(columns) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22472 a probe column claimant_name fails the check and claimant_reference passes`() {
        val columns = catalogue()
        nameColumns(columns + Column("aggregate_transaction", "claimant_name")) shouldBe
            listOf("aggregate_transaction.claimant_name")
        nameColumns(columns + Column("aggregate_transaction", "CLAIMANT_NAME")) shouldBe
            listOf("aggregate_transaction.CLAIMANT_NAME")
        nameColumns(columns + Column("aggregate_transaction", "claimant_reference")) shouldBe emptyList()
        // a table that does not retain underlying transactions is not judged
        nameColumns(columns + Column("some_other_table", "claimant_name")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-2045 the retained list has a claimant reference column and no claimant name column`() {
        val columns = catalogue().filter { it.table == "aggregate_transaction" }.map { it.name }
        ("claimant_reference" in columns) shouldBe true
        columns.none { it.startsWith("claimant") && it.contains("name") } shouldBe true
    }
}
