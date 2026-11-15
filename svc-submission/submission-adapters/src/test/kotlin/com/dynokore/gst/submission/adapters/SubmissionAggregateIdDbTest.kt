package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The aggregate_id column written and read by the store as `submission_runtime` (INV-AC-22807). */
class SubmissionAggregateIdDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqSubmissionStore(dsl)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return block().also { connection.commit() }
    }

    private fun insert(
        tenant: UUID,
        doc: String,
        aggregate: String?,
    ) = asTenant(tenant) {
        store.insertReady(
            TenantId(tenant),
            NewReadySubmission(doc, 1, "e-$doc", null, byteArrayOf(1), "sha-$doc", SubmissionKind.TYPE_1A, aggregate),
        )
    }

    private fun locked(
        tenant: UUID,
        doc: String,
    ) = asTenant(tenant) { store.lockForUpdate(TenantId(tenant), doc, 1) }

    @Test
    fun `INV-AC-22807 D keeps its first aggregateId, E has none, another tenant sees no row`() {
        val t1 = UUID.randomUUID()
        val d = "d-${UUID.randomUUID()}"
        val e = "e-${UUID.randomUUID()}"
        insert(t1, d, "AGG-2026-0001") shouldBe 1
        insert(t1, e, null) shouldBe 1
        insert(t1, d, "AGG-2026-0002") shouldBe 0
        locked(t1, d)!!.aggregateId shouldBe "AGG-2026-0001"
        locked(t1, e)!!.aggregateId shouldBe null
        locked(UUID.randomUUID(), d) shouldBe null
    }
}
