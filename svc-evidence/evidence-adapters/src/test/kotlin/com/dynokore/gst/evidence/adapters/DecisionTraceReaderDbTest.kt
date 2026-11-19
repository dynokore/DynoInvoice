package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqDecisionTraceReader
import com.dynokore.gst.evidence.application.lookup.StoredDecisionTrace
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-20775: the reader answers the highest-seq row of (tenant, doc, version) and nothing else. */
class DecisionTraceReaderDbTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000e1")

    private fun body(vararg codes: String) =
        """{"decisions":[${codes.joinToString(
            ",",
        ) { """{"code":"$it","ruleId":"R","packVersions":[{"packIdentifier":"p","version":"1"}],"inputs":[]}""" }}]}"""

    private fun insert(
        tenant: UUID,
        seq: Long,
        version: Int?,
        content: String,
    ) = EvidenceDb.superuser().use { c ->
        RetentionPartitionFixture.ensureRetentionPartition(c, "decision_trace", tenant, "E", "P")
        c
            .prepareStatement(
                "insert into evidence.decision_trace (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                    "content, content_hash, recorded_at, doc_uuid, version) " +
                    "values (gen_random_uuid(), ?, 'E', 'P', ?, 'DECISION_TRACE', ?, 'h', now(), ?, ?)",
            ).use {
                it.setObject(1, tenant)
                it.setLong(2, seq)
                it.setBytes(3, content.toByteArray())
                it.setObject(4, doc)
                it.setObject(5, version)
                it.executeUpdate()
            }
    }

    private fun latest(
        tenant: UUID,
        version: Int,
    ): StoredDecisionTrace? =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val dsl: DSLContext = DSL.using(c, SQLDialect.POSTGRES)
            JooqDecisionTraceReader(dsl).latest(TenantId(tenant), doc, version).also { c.commit() }
        }

    @Test
    fun `INV-AC-20775 the row with the highest seq answers when the version was recorded twice`() {
        val t = UUID.randomUUID()
        insert(t, 0, 2, body("RUL-001"))
        insert(t, 1, 2, body("RUL-001", "RUL-002"))
        latest(t, 2).shouldBeInstanceOf<StoredDecisionTrace.Valid>().decisions.map { it.code } shouldBe
            listOf("RUL-001", "RUL-002")
    }

    @Test
    fun `INV-AC-20775 a row with a null version is never answered`() {
        val t = UUID.randomUUID()
        insert(t, 0, null, body("RUL-001"))
        latest(t, 1) shouldBe null
    }

    @Test
    fun `INV-AC-20775 another tenant's row is never answered`() {
        val a = UUID.randomUUID()
        insert(a, 0, 1, body("RUL-001"))
        latest(UUID.randomUUID(), 1) shouldBe null
        latest(a, 1).shouldBeInstanceOf<StoredDecisionTrace.Valid>()
    }

    @Test
    fun `INV-AC-20775 version 5 with no row answers null`() {
        val t = UUID.randomUUID()
        insert(t, 0, 1, body("RUL-001"))
        latest(t, 5) shouldBe null
    }

    @Test
    fun `INV-AC-20775 a row whose content is an array answers Malformed`() {
        val t = UUID.randomUUID()
        insert(t, 0, 4, "[]")
        latest(t, 4) shouldBe StoredDecisionTrace.Malformed
    }
}
