package com.dynokore.gst.canonical.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The keys of the partitioned tables (INV-Q-9755) and the value lists and column types (INV-Q-6515, INV-Q-9757). */
class DocumentIdentityTest {
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()

    @Suppress("LongParameterList") // a SQL builder: every argument has a default
    private fun insertDocument(
        tenantId: UUID = tenant,
        entityId: UUID = entity,
        docUuid: UUID = UUID.randomUUID(),
        number: String = "N-1",
        issuer: String = "issuer-1",
        partitionDate: String = "2026-03-15",
        direction: String = "SALE",
        state: String = "READY",
    ) = "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
        "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, current_version, state) " +
        "values ('$docUuid', '$tenantId', '$entityId', '$direction', 380, '$number', '$issuer', 2026, " +
        "'$partitionDate', '$partitionDate', 1, '$state')"

    private fun refusal(
        statement: String,
        tenantId: UUID = tenant,
    ) = DocumentSql.runtimeRefusal(tenantId, statement)

    private fun insertVersion(
        doc: UUID,
        tenantId: UUID,
        entityId: UUID,
        partitionDate: String,
        scope: String = "IN_SCOPE",
        scopePack: String? = "'s'",
        mdePack: String? = "null",
        specification: String = "PINT_SG",
    ) = "insert into canonical.document_version (doc_uuid, version, tenant_id, entity_id, partition_date, canonical, " +
        "scope_verdict, scope_pack_version, mapping_pack_version, mde_pack_version, specification_version, " +
        "created_by) values ('$doc', 1, '$tenantId', '$entityId', '$partitionDate', '{}', '$scope', $scopePack, " +
        "'m', $mdePack, '$specification', 'svc')"

    @Test
    fun `a second document with the same seven values and another partition_date is refused`() {
        DocumentSql.execute(insertDocument(number = "A-1"))
        refusal(insertDocument(number = "A-1", partitionDate = "2027-03-15")) shouldBe "23505"
        refusal(insertDocument(number = "A-1", partitionDate = "2019-03-15")) shouldBe "23505"
    }

    @Test
    fun `the same doc_uuid with another partition_date and entity in the same tenant is refused`() {
        val doc = UUID.randomUUID()
        DocumentSql.execute(insertDocument(docUuid = doc, number = "B-1"))
        refusal(
            insertDocument(docUuid = doc, entityId = UUID.randomUUID(), number = "B-2", partitionDate = "2027-01-02"),
        ) shouldBe
            "23505"
    }

    @Test
    fun `the same doc_uuid in tenant B while tenant A holds it is refused`() {
        val doc = UUID.randomUUID()
        val other = UUID.randomUUID()
        DocumentSql.execute(insertDocument(docUuid = doc, number = "C-1"))
        refusal(
            insertDocument(
                tenantId = other,
                entityId = UUID.randomUUID(),
                docUuid = doc,
                number = "C-2",
                partitionDate = "2027-02-03",
            ),
            other,
        ) shouldBe "23505"
    }

    @Test
    fun `look-alikes are accepted`() {
        DocumentSql.execute(insertDocument(number = "D-1"))
        refusal(insertDocument(number = "D-1", issuer = "issuer-2")) shouldBe null
        val other = UUID.randomUUID()
        refusal(insertDocument(tenantId = other, number = "D-1"), other) shouldBe null
    }

    @Test
    fun `two concurrent transactions with the same seven values in two year partitions commit exactly once`() {
        repeat(ROUNDS) { round ->
            val number = "RACE-$round-${UUID.randomUUID()}"
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = open()
                first
                    .prepareStatement(
                        insertDocument(number = number, partitionDate = "2026-05-01"),
                    ).use { it.executeUpdate() }
                // the second insert waits on the advisory lock the first holds, or, without the lock, goes through
                val second =
                    pool.submit<String?> {
                        open().use { connection ->
                            try {
                                connection
                                    .prepareStatement(
                                        insertDocument(number = number, partitionDate = "2027-05-01"),
                                    ).use { it.executeUpdate() }
                                connection.commit()
                                null
                            } catch (refused: java.sql.SQLException) {
                                connection.rollback()
                                refused.sqlState
                            }
                        }
                    }
                Thread.sleep(SETTLE_MILLIS)
                first.commit()
                first.close()
                second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe "23505"
                DocumentSql.one("select count(*) from canonical.document where document_number = ?", number) shouldBe
                    "1"
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun open(): Connection =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).also {
            it.autoCommit = false
            SignedBindings.bind(it, tenant)
        }

    @Test
    fun `a version whose partition_date differs from its document is refused by the foreign key`() {
        val doc = UUID.randomUUID()
        DocumentSql.execute(insertDocument(docUuid = doc, number = "E-1"))
        refusal(insertVersion(doc, tenant, entity, "2026-03-15")) shouldBe null
        refusal(insertVersion(doc, tenant, entity, "2027-03-15")) shouldBe "23503"
    }

    @Test
    fun `each listed value is accepted and each look-alike is refused by the column`() {
        listOf("SALE", "PURCHASE").forEach { refusal(insertDocument(direction = it, number = "V-$it")) shouldBe null }
        listOf("INGESTED", "HELD", "BLOCKED", "NOT_SUBMITTABLE", "READY").forEach {
            refusal(insertDocument(state = it, number = "S-$it")) shouldBe null
        }
        refusal(insertDocument(direction = "SUPPLY", number = "V-X")) shouldBe "23514"
        listOf("QUEUED", "AWAITING_PRECEDING", "READY ").forEach {
            refusal(insertDocument(state = it, number = "S-X")) shouldBe "23514"
        }
        val doc = UUID.randomUUID()
        DocumentSql.execute(insertDocument(docUuid = doc, number = "F-1"))
        listOf("IN_SCOPE", "EXCLUDED_TXN", "OUT_OF_SCOPE").forEach {
            val own = UUID.randomUUID()
            DocumentSql.execute(insertDocument(docUuid = own, number = "F-$it"))
            refusal(insertVersion(own, tenant, entity, "2026-03-15", scope = it)) shouldBe null
        }
        refusal(insertVersion(doc, tenant, entity, "2026-03-15", scope = "SUBMITTABLE")) shouldBe "23514"
        listOf("PINT_SG", "SG_PEPPOL_BIS").forEach {
            val other = UUID.randomUUID()
            DocumentSql.execute(insertDocument(docUuid = other, number = "G-$it"))
            refusal(insertVersion(other, tenant, entity, "2026-03-15", specification = it)) shouldBe null
        }
    }

    @Test
    fun `pack version lengths and nullability are enforced`() {
        val doc = UUID.randomUUID()
        DocumentSql.execute(insertDocument(docUuid = doc, number = "H-1"))
        refusal(insertVersion(doc, tenant, entity, "2026-03-15", scopePack = "'${"x".repeat(33)}'")) shouldBe "22001"
        refusal(insertVersion(doc, tenant, entity, "2026-03-15", scopePack = "null")) shouldBe "23502"
        refusal(insertVersion(doc, tenant, entity, "2026-03-15", scopePack = "'${"x".repeat(32)}'")) shouldBe null
        refusal(insertVersion(doc, tenant, entity, "2026-03-15", mdePack = "null")) shouldBe null
    }

    private companion object {
        const val ROUNDS = 3
        const val SETTLE_MILLIS = 700L
        const val TIMEOUT_SECONDS = 20L
    }
}
