package com.dynokore.gst.canonical.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The tables of INV-NOTE-3 section 10.2 and the identity key of section 10.3. */
class DocumentStorageTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    @Test
    fun `a second document with the same seven values is rejected`() {
        val first = DocumentKit.document(tenant, entity, day, number = "SAME-1")
        kit.insert(first)
        val sameDay = DocumentKit.document(tenant, entity, day, number = "SAME-1")
        DocumentSql.sqlState(shouldThrow<Exception> { kit.insert(sameDay) }) shouldBe "23505"
        // another transaction date is another partition: the key still holds across partitions
        val otherDay = DocumentKit.document(tenant, entity, day.plusYears(1), number = "SAME-1")
        DocumentSql.sqlState(shouldThrow<Exception> { kit.insert(otherDay) }) shouldBe "23505"
        DocumentSql.one("select count(*) from canonical.document where tenant_id = ?", tenant) shouldBe "1"
    }

    @Test
    fun `a reused doc_uuid is rejected across partitions`() {
        val first = DocumentKit.document(tenant, entity, day)
        kit.insert(first)
        val again = DocumentKit.document(tenant, entity, day.plusYears(1), docUuid = first.docUuid)
        DocumentSql.sqlState(shouldThrow<Exception> { kit.insert(again) }) shouldBe "23505"
    }

    @Test
    fun `the same document number with another issuer party key is accepted`() {
        kit.insert(DocumentKit.document(tenant, entity, day, number = "SAME-2", issuer = "supplier-a"))
        kit.insert(DocumentKit.document(tenant, entity, day, number = "SAME-2", issuer = "supplier-b"))
        DocumentSql.one("select count(*) from canonical.document where tenant_id = ?", tenant) shouldBe "2"
    }

    @Test
    fun `a document number of exactly 100 characters is stored and read back and one of 101 is refused`() {
        val hundred = "N".repeat(100)
        val stored = DocumentKit.document(tenant, entity, day, number = hundred)
        kit.insert(stored)
        DocumentSql.documentColumn("document_number", stored.docUuid) shouldBe hundred
        // the domain object refuses 101 before a statement; the column refuses it too
        shouldThrow<IllegalArgumentException> { DocumentKit.document(tenant, entity, day, number = "N".repeat(101)) }
        DocumentSql.runtimeRefusal(tenant, insertWithNumber("N".repeat(101))) shouldBe "22001"
        DocumentSql.runtimeRefusal(tenant, insertWithNumber(hundred)) shouldBe null
    }

    private fun insertWithNumber(number: String) = DocumentSql.insertOf(tenant, entity, number)

    @Test
    fun `the columns have the types and nullability of section 10 2`() {
        fun columns(table: String) =
            DocumentSql
                .rows(
                    "select column_name, data_type, coalesce(character_maximum_length::text, ''), is_nullable " +
                        "from information_schema.columns where table_schema = 'canonical' and table_name = ? " +
                        "order by ordinal_position",
                    table,
                ).associate { it[0]!! to Triple(it[1], it[2], it[3]) }
        val document = columns("document")
        document["document_number"] shouldBe Triple("character varying", "100", "NO")
        document["issuer_party_key"] shouldBe Triple("character varying", "64", "NO")
        document["internal_reference"] shouldBe Triple("character varying", "64", "YES")
        document["business_unit_id"] shouldBe Triple("uuid", "", "YES")
        document["prescribed_period_id"] shouldBe Triple("uuid", "", "YES")
        document["document_type_code"] shouldBe Triple("smallint", "", "NO")
        document["fiscal_year"] shouldBe Triple("smallint", "", "NO")
        document["current_version"] shouldBe Triple("integer", "", "NO")
        document["partition_date"] shouldBe Triple("date", "", "NO")
        document["transaction_date"] shouldBe Triple("date", "", "NO")
        listOf("tenant_id", "entity_id", "doc_uuid").forEach { document[it] shouldBe Triple("uuid", "", "NO") }
        val version = columns("document_version")
        version["canonical"] shouldBe Triple("jsonb", "", "NO")
        listOf("tenant_id", "entity_id").forEach { version[it] shouldBe Triple("uuid", "", "NO") }
        version["partition_date"] shouldBe Triple("date", "", "NO")
        version["version"] shouldBe Triple("integer", "", "NO")
        version["mde_pack_version"] shouldBe Triple("character varying", "32", "YES")
        listOf("scope_pack_version", "mapping_pack_version").forEach {
            version[it] shouldBe
                Triple("character varying", "32", "NO")
        }
    }

    @Test
    fun `a tenant A session sees no document of tenant B`() {
        val other = UUID.randomUUID()
        kit.insert(DocumentKit.document(other, entity, day))
        DocumentSql.runtimeRefusal(tenant, insertWithNumber("X")) shouldBe null
        DocumentSql.one("select count(*) from canonical.document where tenant_id = ?", other) shouldBe "1"
        // a row of tenant B cannot be written under the binding of tenant A
        DocumentSql.runtimeRefusal(
            tenant,
            insertWithNumber("Y").replace("'$tenant'", "'$other'"),
        ) shouldBe "42501"
    }
}
