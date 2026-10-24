package com.dynokore.gst.canonical.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-REQ-637 in the database: the runtime login cannot update a version or the partition date. */
class DocumentGrantsTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val document = DocumentKit.document(tenant, UUID.randomUUID(), LocalDate.of(2026, 3, 15))

    private val versionColumns =
        DocumentSql
            .rows(
                "select column_name from information_schema.columns " +
                    "where table_schema = 'canonical' and table_name = 'document_version'",
            ).map { it[0]!! }

    private fun refusalOfUpdate(
        table: String,
        column: String,
    ) = DocumentSql.runtimeRefusal(tenant, "update canonical.$table set $column = $column")

    @Test
    fun `INV-AC-1712 an UPDATE of any column of document_version fails for the runtime login`() {
        kit.insert(document)
        (versionColumns.size >= VERSION_COLUMNS) shouldBe true
        versionColumns.forEach { refusalOfUpdate("document_version", it) shouldBe "42501" }
        DocumentSql.runtimeRefusal(tenant, "delete from canonical.document_version") shouldBe "42501"
        DocumentSql.runtimeRefusal(tenant, "delete from canonical.document") shouldBe "42501"
    }

    @Test
    fun `INV-AC-1712 an UPDATE of partition_date fails and the four mutable columns of document are accepted`() {
        kit.insert(document)
        listOf(
            "partition_date",
            "doc_uuid",
            "tenant_id",
            "entity_id",
            "direction",
            "document_number",
            "issuer_party_key",
        ).forEach { refusalOfUpdate("document", it) shouldBe "42501" }
        listOf("current_version", "state", "transaction_date", "prescribed_period_id")
            .forEach { refusalOfUpdate("document", it) shouldBe null }
    }
}

private const val VERSION_COLUMNS = 12
