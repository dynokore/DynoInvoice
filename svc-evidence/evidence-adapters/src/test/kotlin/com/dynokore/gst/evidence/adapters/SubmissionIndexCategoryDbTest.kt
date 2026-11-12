package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexCategoryStore
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20767: the category child rows of `evidence.submission_index`, one per distinct line tax code. */
class SubmissionIndexCategoryDbTest {
    private val doc = UUID.randomUUID()

    private fun <T> tx(
        tenant: UUID,
        block: (DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            block(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

    private fun parent(
        tenant: UUID,
        version: Int = 1,
    ) = EvidenceDb.superuser().use { c ->
        c.createStatement().use {
            it.execute(
                "insert into evidence.submission_index (tenant_id, doc_uuid, version, recorded_at) " +
                    "values ('$tenant', '$doc', $version, now())",
            )
        }
    }

    private fun insert(
        tenant: UUID,
        version: Int,
        vararg codes: String,
    ) = tx(tenant) { dsl, t ->
        JooqSubmissionIndexCategoryStore(dsl).insertAll(t, doc, version, codes.toCollection(LinkedHashSet()))
    }

    private fun rows(tenant: UUID): List<Pair<Int, String>> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select version, category_code from evidence.submission_index_category " +
                        "where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) }
                            .sortedWith(compareBy({ it.first }, { it.second }))
                    }
                }
        }

    private fun refused(
        tenant: UUID,
        sql: String,
    ): SQLException =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val failure = runCatching { c.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            c.rollback()
            failure as SQLException
        }

    @Test
    fun `INV-AC-20767 insertAll of two codes writes two rows and a repeat writes nothing new`() {
        val t = UUID.randomUUID()
        parent(t)
        insert(t, 1, "SR", "ZR")
        rows(t) shouldBe listOf(1 to "SR", 1 to "ZR")
        insert(t, 1, "SR", "ZR")
        rows(t) shouldBe listOf(1 to "SR", 1 to "ZR")
    }

    @Test
    fun `INV-AC-20767 a code in a second version of the same document is a second row`() {
        val t = UUID.randomUUID()
        parent(t, 1)
        parent(t, 2)
        insert(t, 1, "SR")
        insert(t, 2, "SR")
        rows(t) shouldBe listOf(1 to "SR", 2 to "SR")
    }

    @Test
    fun `INV-AC-20767 a child row without a parent row is refused by the foreign key`() {
        val t = UUID.randomUUID()
        val failure = runCatching { insert(t, 1, "SR") }.exceptionOrNull()
        (failure as org.jooq.exception.DataAccessException).sqlState() shouldBe "23503"
        rows(t) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20767 a second tenant reads nothing`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        parent(a)
        insert(a, 1, "SR")
        tx(b) { dsl, _ -> dsl.fetchCount(DSL.table(DSL.name("evidence", "submission_index_category"))) } shouldBe 0
    }

    @Test
    fun `INV-AC-20767 evidence_runtime has SELECT and INSERT and no UPDATE and no DELETE`() {
        val t = UUID.randomUUID()
        parent(t)
        insert(t, 1, "SR")
        refused(t, "update evidence.submission_index_category set category_code = 'X'").sqlState shouldBe "42501"
        refused(t, "delete from evidence.submission_index_category").sqlState shouldBe "42501"
        EvidenceDb.superuser().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select has_table_privilege('evidence_runtime', 'evidence.submission_index_category', " +
                            "'SELECT'), has_table_privilege('evidence_runtime', " +
                            "'evidence.submission_index_category', 'INSERT')",
                    ).use {
                        it.next()
                        listOf(it.getBoolean(1), it.getBoolean(2))
                    }
            } shouldBe listOf(true, true)
        }
    }

    @Test
    fun `INV-AC-20767 a locator transaction rolled back leaves no child row`() {
        val t = UUID.randomUUID()
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            val dsl = DSL.using(c, SQLDialect.POSTGRES)
            val locator = DocumentLocator(doc, 1, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "tr", "INV-1")
            ChainWiring
                .locatorRecorder(dsl)
                .record(TenantId(t), "e1", locator, null, linkedSetOf("SR", "ZR")) shouldBe true
            dsl.fetchCount(DSL.table(DSL.name("evidence", "submission_index_category"))) shouldBe 2
            c.rollback()
        }
        rows(t) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20767 the look-alike codes sr and SR are two rows`() {
        val t = UUID.randomUUID()
        parent(t)
        insert(t, 1, "sr", "SR")
        rows(t) shouldBe listOf(1 to "SR", 1 to "sr")
    }
}
