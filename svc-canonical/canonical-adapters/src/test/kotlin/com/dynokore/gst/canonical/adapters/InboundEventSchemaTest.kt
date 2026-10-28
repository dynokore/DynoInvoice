package com.dynokore.gst.canonical.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** `canonical.inbound_event` (INV-T-6700; INV-REQ-429): append-only, one row per version, the table checks. */
class InboundEventSchemaTest {
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()

    @Suppress("LongParameterList") // a test builder: every argument has a default
    private fun insert(
        owner: UUID = tenant,
        doc: UUID = UUID.randomUUID(),
        version: Int = 1,
        kind: String = "RECEIPT",
        key: String? = "0088:123",
        number: String? = "N-1",
        year: Int? = 2026,
        endpoint: String? = "0088:123",
        supplierUuid: UUID? = UUID.randomUUID(),
        disposition: String? = "PENDING",
    ): String =
        "insert into canonical.inbound_event (tenant_id, entity_id, doc_uuid, version, event_kind, " +
            "document_function, supplier_key, document_number, document_year, supplier_endpoint_id, " +
            "supplier_doc_uuid, buyer_disposition) values ('$owner', '$entity', '$doc', $version, '$kind', " +
            "'INVOICE', ${lit(key)}, ${lit(number)}, ${year ?: "null"}, ${lit(endpoint)}, ${lit(supplierUuid)}, " +
            "${lit(disposition)})"

    private fun lit(value: Any?) = if (value == null) "null" else "'$value'"

    private fun refusal(statement: String) = DocumentSql.runtimeRefusal(tenant, statement)

    private fun posting(
        doc: UUID = UUID.randomUUID(),
        version: Int = 1,
    ) = insert(doc = doc, version = version, kind = "POSTING", endpoint = null, supplierUuid = null, disposition = null)

    @Test
    fun `a receipt row and a posting row are accepted`() {
        refusal(insert()) shouldBe null
        refusal(posting()) shouldBe null
    }

    @Test
    fun `a receipt row without supplier_doc_uuid is accepted`() {
        refusal(insert(supplierUuid = null)) shouldBe null
    }

    @Test
    fun `a second row with the same doc_uuid and version is refused`() {
        val doc = UUID.randomUUID()
        val state =
            CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, tenant)
                connection.createStatement().use { it.executeUpdate(insert(doc = doc)) }
                val failure =
                    try {
                        connection.createStatement().use { it.executeUpdate(insert(doc = doc)) }
                        null
                    } catch (refused: java.sql.SQLException) {
                        refused.sqlState
                    }
                connection.rollback()
                failure
            }
        state shouldBe "23505"
    }

    @Test
    fun `a receipt row without buyer_disposition is refused`() {
        refusal(insert(disposition = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a receipt row without supplier_endpoint_id is refused`() {
        refusal(insert(endpoint = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a posting row with buyer_disposition is refused`() {
        refusal(insert(kind = "POSTING", endpoint = null, supplierUuid = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a posting row with supplier_endpoint_id is refused`() {
        refusal(insert(kind = "POSTING", supplierUuid = null, disposition = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a posting row with supplier_doc_uuid is refused`() {
        refusal(insert(kind = "POSTING", endpoint = null, disposition = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a row with supplier_key null and document_number non-null is refused`() {
        refusal(insert(key = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a row with only document_year null or only document_number null is refused`() {
        refusal(insert(year = null)) shouldBe CHECK_VIOLATION
        refusal(insert(number = null)) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a row with the three fallback columns null is accepted`() {
        refusal(insert(key = null, number = null, year = null)) shouldBe null
    }

    @Test
    fun `an enum value outside the set is refused`() {
        refusal(insert(disposition = "MAYBE")) shouldBe CHECK_VIOLATION
        refusal(insert().replace("'INVOICE'", "'QUOTE'")) shouldBe CHECK_VIOLATION
    }

    @Test
    fun `a cross-tenant read through a session of tenant A returns zero rows of tenant B`() {
        val other = UUID.randomUUID()
        DocumentSql.execute(insert(owner = other))
        DocumentSql.execute(insert())
        val seen =
            CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, tenant)
                connection.prepareStatement("select count(*) from canonical.inbound_event").use {
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
        seen shouldBe 1 // only the row of tenant A; the row of tenant B is invisible
    }

    /** The runtime login's UPDATE and DELETE are refused with insufficient_privilege. */
    private fun updateAndDeleteAreRefused(): Boolean =
        refusal("update canonical.inbound_event set version = version + 1") == INSUFFICIENT_PRIVILEGE &&
            refusal("delete from canonical.inbound_event") == INSUFFICIENT_PRIVILEGE

    @Test
    fun `the runtime login's UPDATE and DELETE fail`() {
        DocumentSql.execute(insert())
        updateAndDeleteAreRefused() shouldBe true
    }

    @Test
    fun `probe a scratch grant of UPDATE makes the grants check fail`() {
        DocumentSql.execute(insert())
        CanonicalDb.connect(CanonicalDb.MIGRATE, CanonicalDb.MIGRATE_PASSWORD).use { owner ->
            owner.createStatement().use { it.execute("grant update on canonical.inbound_event to canonical_runtime") }
            val refusedWithGrant = updateAndDeleteAreRefused()
            owner.createStatement().use {
                it.execute("revoke update on canonical.inbound_event from canonical_runtime")
            }
            refusedWithGrant shouldBe false // the probe: the committed test would fail here
        }
        updateAndDeleteAreRefused() shouldBe true
    }

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
