package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * INV-Q-7213 A: the four chain tables hold exactly the columns of `JdbcEvidenceLedger.COLUMNS`, and the seven stores of
 * the slice are append-only and tenant-isolated (CS-19, CS-22).
 */
class ChainTablesTest {
    private val chainTables = RecordKind.entries.map { it.table }
    private val storeTables = listOf("document_locator", "pending_record", "applied_record")
    private val allTables = chainTables + storeTables

    /** The column names `COLUMNS` declares: its comma-separated items without the table constraints. */
    private fun expectedColumns(): List<String> =
        topLevelItems(JdbcEvidenceLedger.COLUMNS)
            .filterNot { it.startsWith("unique") }
            .map { it.substringBefore(' ') }

    /** The items of a column list separated by a comma outside parentheses. */
    private fun topLevelItems(text: String): List<String> {
        val items = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, char ->
            when {
                char == '(' -> {
                    depth++
                }

                char == ')' -> {
                    depth--
                }

                char == ',' && depth == 0 -> {
                    items += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        return items + text.substring(start).trim()
    }

    private fun columnsOf(
        connection: Connection,
        table: String,
    ): List<String> =
        connection
            .prepareStatement(
                "select column_name from information_schema.columns " +
                    "where table_schema = 'evidence' and table_name = ? order by ordinal_position",
            ).use {
                it.setString(1, table)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }

    /** The chain tables whose columns differ from `COLUMNS`, with the difference. */
    private fun columnFindings(connection: Connection): List<String> =
        chainTables.mapNotNull { table ->
            val actual = columnsOf(connection, table)
            if (actual == expectedColumns()) null else "$table: $actual"
        }

    @Test
    fun `the values of RecordKind equal the ledger shaped tables of the schema, both ways`() {
        val shaped =
            EvidenceDb.superuser().use { connection ->
                connection
                    .prepareStatement(
                        "select table_name from information_schema.columns where table_schema = 'evidence' " +
                            "and column_name = 'content_hash' and table_name <> 'flyway_schema_history'",
                    ).use {
                        it.executeQuery().use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                        }
                    }
            }
        shaped shouldBe chainTables.toSet()
    }

    @Test
    fun `each chain table has exactly the columns of JdbcEvidenceLedger COLUMNS`() {
        expectedColumns().size shouldBe 14
        EvidenceDb.superuser().use { columnFindings(it).shouldBeEmpty() }
    }

    @Test
    fun `a chain table with an added source_event_id column is a finding`() {
        EvidenceDb.withScratchDatabase(
            foreignSetup = "select 1",
            probeMigration = "alter table evidence.decision_trace add column source_event_id text;",
        ) { url ->
            EvidenceDb.superuserOf(url).use { columnFindings(it).size shouldBe 1 }
        }
    }

    private fun insert(table: String): String =
        when (table) {
            "document_locator" -> {
                "insert into evidence.document_locator values (?, ?, 1, 'E', 'P', '2026-01-01', 't', null, ?, now())"
            }

            "pending_record" -> {
                "insert into evidence.pending_record values (?, ?, 'decision_trace', ?, 1, '{}', now())"
            }

            "applied_record" -> {
                "insert into evidence.applied_record values (?, ?, 'decision_trace', 'E', 'P', 0, now())"
            }

            else -> {
                "insert into evidence.$table (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, " +
                    "content_hash, recorded_at) values (gen_random_uuid(), ?, 'E', ?, 0, 'T', '\\x00', ?, now())"
            }
        }

    private fun store(
        connection: Connection,
        table: String,
        tenant: UUID,
    ) {
        val text = UUID.randomUUID().toString()
        connection.prepareStatement(insert(table)).use {
            it.setObject(1, tenant)
            if (table == "document_locator") {
                it.setObject(2, UUID.randomUUID())
                it.setString(3, text)
            } else if (table == "pending_record") {
                it.setString(2, text)
                it.setObject(3, UUID.randomUUID())
            } else if (table == "applied_record") {
                it.setString(2, text)
            } else {
                it.setString(2, text)
                it.setString(3, text)
            }
            it.executeUpdate()
        }
    }

    private fun runtime(block: (Connection) -> Unit) =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use {
            it.autoCommit = false
            block(it)
        }

    private fun migrate(block: (Connection) -> Unit) =
        EvidenceDb.connect(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use {
            it.autoCommit = false
            block(it)
        }

    private fun refused(
        connection: Connection,
        sql: String,
    ): String? {
        val result = runCatching { connection.createStatement().use { it.execute(sql) } }
        connection.rollback()
        return result.exceptionOrNull()?.let { (it as SQLException).sqlState }
    }

    @Test
    fun `UPDATE DELETE and TRUNCATE are refused for the runtime role on each of the seven tables`() {
        val tenant = UUID.randomUUID()
        allTables.forEach { table ->
            runtime { connection ->
                SignedBindings.bind(connection, tenant)
                store(connection, table, tenant)
                connection.commit()
                SignedBindings.bind(connection, tenant)
                refused(connection, "update evidence.$table set tenant_id = tenant_id") shouldBe "42501"
                SignedBindings.bind(connection, tenant)
                refused(connection, "delete from evidence.$table") shouldBe "42501"
                refused(connection, "truncate evidence.$table") shouldBe "42501"
            }
        }
    }

    @Test
    fun `UPDATE and DELETE are refused by the trigger even for the owner on each of the seven tables`() {
        val tenant = UUID.randomUUID()
        allTables.forEach { table ->
            runtime { connection ->
                SignedBindings.bind(connection, tenant)
                store(connection, table, tenant)
                connection.commit()
            }
            migrate { connection ->
                SignedBindings.bind(connection, tenant)
                val update =
                    runCatching {
                        connection.createStatement().use {
                            it.execute(
                                "update evidence.$table set tenant_id = tenant_id",
                            )
                        }
                    }
                (update.exceptionOrNull() as SQLException).message!!.contains("CS-22") shouldBe true
                connection.rollback()
                SignedBindings.bind(connection, tenant)
                val delete =
                    runCatching { connection.createStatement().use { it.execute("delete from evidence.$table") } }
                (delete.exceptionOrNull() as SQLException).message!!.contains("CS-22") shouldBe true
                connection.rollback()
            }
        }
    }

    @Test
    fun `a session of one tenant reads no row of another tenant on each of the seven tables`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        allTables.forEach { table ->
            runtime { connection ->
                SignedBindings.bind(connection, a)
                store(connection, table, a)
                connection.commit()
                SignedBindings.bind(connection, b)
                store(connection, table, b)
                connection.commit()
                SignedBindings.bind(connection, a)
                connection
                    .prepareStatement("select count(*) from evidence.$table where tenant_id = ?")
                    .use {
                        it.setObject(1, b)
                        it.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1) shouldBe 0
                        }
                    }
                connection.rollback()
            }
        }
    }

    @Test
    fun `a write for another tenant is refused on each of the seven tables`() {
        val a = UUID.randomUUID()
        allTables.forEach { table ->
            runtime { connection ->
                SignedBindings.bind(connection, a)
                shouldThrow<SQLException> { store(connection, table, UUID.randomUUID()) }.sqlState shouldBe "42501"
                connection.rollback()
            }
        }
    }
}
