package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqUnsubmittedDocumentSource
import com.dynokore.gst.submission.application.offboarding.CountUnsubmittedDocuments
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** The unsubmitted-document reader against the test database as `submission_runtime` with the tenant bound. */
class UnsubmittedDocumentSourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }

    private val useCase = CountUnsubmittedDocuments(JooqUnsubmittedDocumentSource(dsl, runner), Clock.systemUTC())

    @AfterEach
    fun close() = connection.close()

    private fun countFor(
        bindTo: UUID,
        tenant: UUID,
        entity: String?,
    ): Int {
        bound.set(bindTo)
        try {
            return useCase.execute(TenantId(tenant), entity).unsubmittedDocumentCount
        } finally {
            bound.remove()
        }
    }

    private fun seed(
        tenant: UUID,
        entity: String,
        doc: String,
        inScope: Boolean,
        vararg states: String,
    ) {
        bound.set(tenant)
        try {
            runner.inTransaction {
                dsl.execute(
                    "insert into submission.obligation_register_entry " +
                        "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                        "values (?, ?, ?, ?, ?, ?, ?)",
                    tenant, doc, entity, "p1", LocalDate.of(2026, 11, 30), inScope, "INVOICE",
                )
                states.forEachIndexed { index, state ->
                    dsl.execute(
                        "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                            "values (?, ?, ?, ?, ?)",
                        tenant, doc, index + 1, state, entity,
                    )
                }
            }
        } finally {
            bound.remove()
        }
    }

    private fun fixture(): Triple<UUID, UUID, UUID> {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val t3 = UUID.randomUUID()
        val s = UUID.randomUUID().toString().take(8)
        val e1 = "E1-$s"
        val e2 = "E2-$s"
        entities = Triple(e1, e2, "E3-$s")
        seed(t1, e1, "D1-$s", true, "ACKNOWLEDGED")
        seed(t1, e1, "D2-$s", true, "QUEUED")
        seed(t1, e1, "D3-$s", true, "SENT")
        seed(t1, e1, "D4-$s", true, "REJECTED")
        seed(t1, e1, "D5-$s", true, "ACKNOWLEDGED", "REJECTED")
        seed(t1, e1, "D6-$s", true, "CANCELLED")
        seed(t1, e1, "D7-$s", false, "QUEUED")
        seed(t1, e2, "D8-$s", true, "QUEUED")
        seed(t1, e2, "D10-$s", true)
        seed(t2, "E3-$s", "D9-$s", true, "QUEUED")
        return Triple(t1, t2, t3)
    }

    private var entities = Triple("", "", "")

    @Test
    fun `INV-AC-22870 the counts of tenant and entity equal the unsubmitted in-scope documents`() {
        val (t1, t2, t3) = fixture()
        countFor(t1, t1, null) shouldBe 5
        countFor(t1, t1, entities.first) shouldBe 3
        countFor(t1, t1, entities.second) shouldBe 2
        countFor(t2, t2, null) shouldBe 1
        countFor(t3, t3, null) shouldBe 0
    }

    @Test
    fun `INV-AC-22870 the reader bound to T2 and asked for T1 answers 0`() {
        val (t1, t2, _) = fixture()
        countFor(t2, t1, null) shouldBe 0
        countFor(t2, t1, entities.first) shouldBe 0
    }
}
