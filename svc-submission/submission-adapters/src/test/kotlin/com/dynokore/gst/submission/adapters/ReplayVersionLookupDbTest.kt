package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqReplayVersionLookup
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.replay.ReplayVersionLookup
import com.dynokore.gst.submission.application.replay.ReplayVersionView
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The replay version lookup against the test database as `submission_runtime`. */
class ReplayVersionLookupDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                }
            }
        }
    private val lookup: ReplayVersionLookup = JooqReplayVersionLookup(dsl, runner)
    private val sub = Submission.SUBMISSION_
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val s = UUID.randomUUID().toString().take(8)
    private val doc = "d-$s"

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return runner.inTransaction(block)
        } finally {
            bound.remove()
        }
    }

    private fun seed(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
        entity: String,
    ) = asTenant(tenant) {
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, version)
            .set(sub.STATE, state)
            .set(sub.ENTITY_REF, entity)
            .execute()
    }

    private fun find(
        tenant: UUID,
        docUuid: String,
        version: Int,
    ) = asTenant(tenant) { lookup.find(TenantId(tenant), docUuid, version) }

    private fun snapshot(tenant: UUID) =
        asTenant(tenant) {
            dsl
                .select(sub.DOC_UUID, sub.VERSION, sub.STATE, sub.ENTITY_REF)
                .from(sub)
                .fetch()
                .map { it.intoList() }
        }

    @Test
    fun `INV-AC-20590 the lookup returns entityRef and state of an existing version`() {
        seed(tenantA, doc, 2, "DEAD_LETTERED", "E1-$s")
        seed(tenantA, doc, 3, "SENT", "E1-$s")
        find(tenantA, doc, 2) shouldBe ReplayVersionView("E1-$s", LifecycleState.DEAD_LETTERED)
        find(tenantA, doc, 3) shouldBe ReplayVersionView("E1-$s", LifecycleState.SENT)
    }

    @Test
    fun `INV-AC-20590 the lookup returns null for an unknown version and another version number`() {
        seed(tenantA, doc, 2, "DEAD_LETTERED", "E1-$s")
        find(tenantA, doc, 1) shouldBe null
        find(tenantA, doc, 20) shouldBe null
        find(tenantA, "other-$s", 2) shouldBe null
    }

    @Test
    fun `INV-AC-20590 the lookup returns null for the version of a second tenant`() {
        seed(tenantB, doc, 2, "DEAD_LETTERED", "E9-$s")
        find(tenantA, doc, 2) shouldBe null
        // the tenant filter holds even when the session is bound to the owner of the row
        asTenant(tenantB) { lookup.find(TenantId(tenantA), doc, 2) } shouldBe null
        find(tenantB, doc, 2) shouldBe ReplayVersionView("E9-$s", LifecycleState.DEAD_LETTERED)
    }

    @Test
    fun `INV-AC-20590 the lookup writes no row`() {
        seed(tenantA, doc, 2, "DEAD_LETTERED", "E1-$s")
        val before = snapshot(tenantA)
        find(tenantA, doc, 2)
        find(tenantA, doc, 9)
        snapshot(tenantA) shouldBe before
    }
}
