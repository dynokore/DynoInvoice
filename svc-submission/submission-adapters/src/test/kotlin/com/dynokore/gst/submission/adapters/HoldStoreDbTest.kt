package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqHeldTenantSource
import com.dynokore.gst.submission.adapters.persistence.JooqHoldStore
import com.dynokore.gst.submission.application.ordering.HeldVersion
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/** INV-AC-24517: [JooqHoldStore] and [JooqHeldTenantSource] over PostgreSQL as `submission_runtime`, RLS in force. */
class HoldStoreDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val run = UUID.randomUUID().toString().take(RUN_LEN)
    private val c1 = "c1-$run"
    private val c2 = "c2-$run"
    private val c3 = "c3-$run"
    private val c4 = "c4-$run"
    private val c5 = "c5-$run"
    private val p1 = "p1-$run"
    private val p2 = "p2-$run"

    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqHoldStore(dsl)
    private val source = JooqHeldTenantSource(dsl)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                it.execute("delete from submission.submission where tenant_id in ('$t1', '$t2')")
                it.execute("delete from submission.obligation_register_entry where tenant_id in ('$t1', '$t2')")
            }
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun seed() {
        SubmissionDb.superuser().use { c ->
            fun entry(
                tenant: UUID,
                doc: String,
                preceding: String,
            ) = c.exec(
                "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                    "due_date, in_scope, kind, preceding_doc_uuid) " +
                    "values ('$tenant', '$doc', 'E-001', '2026-Q1', current_date, true, 'CREDIT_NOTE', '$preceding')",
            )

            fun row(
                tenant: UUID,
                doc: String,
                version: Int,
                state: String,
            ) = c.exec(
                "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                    "values ('$tenant', '$doc', $version, '$state', 'E-001')",
            )
            entry(t1, c1, p1)
            entry(t1, c2, p1)
            entry(t1, c3, p2)
            entry(t2, c5, p1)
            row(t1, c1, 1, "READY")
            row(t1, c2, 1, "AWAITING_PRECEDING")
            row(t1, c3, 1, "AWAITING_PRECEDING")
            row(t1, c4, 1, "AWAITING_PRECEDING")
            row(t1, c1, 2, "QUEUED")
            row(t2, c5, 1, "AWAITING_PRECEDING")
        }
    }

    private fun <T> inTx(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return try {
            block().also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    private fun stateOf(
        tenant: UUID,
        doc: String,
        version: Int,
    ): String? =
        SubmissionDb.superuser().use { c ->
            c.prepareStatement("select state from submission.submission where tenant_id = ? and doc_uuid = ? and version = ?").use {
                it.setObject(1, tenant)
                it.setString(2, doc)
                it.setInt(3, version)
                it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** The tenants of this test among those the function lists: the database is shared with other test classes. */
    private fun mine(): Set<UUID> = source.tenants().map { it.value }.toSet().intersect(setOf(t1, t2))

    private fun hv(
        doc: String,
        preceding: String,
    ) = HeldVersion(doc, 1, preceding, "E-001", "2026-Q1")

    @Test
    fun `INV-AC-24517 hold and release move one version by state, the lists join the register and the function lists tenants`() {
        seed()
        val a = TenantId(t1)
        val b = TenantId(t2)
        mine() shouldBe setOf(t1, t2)
        inTx(t1) { store.holdReady(a, c1, 1) } shouldBe 1
        stateOf(t1, c1, 1) shouldBe "AWAITING_PRECEDING"
        inTx(t1) { store.holdReady(a, c1, 2) } shouldBe 0
        stateOf(t1, c1, 2) shouldBe "QUEUED"
        inTx(t1) { store.heldBy(a, p1) } shouldBe listOf(hv(c1, p1), hv(c2, p1))
        val all = inTx(t1) { store.held(a, 10) }
        all shouldBe listOf(hv(c1, p1), hv(c2, p1), hv(c3, p2))
        inTx(t1) { store.held(a, 1) } shouldBe all.take(1)
        inTx(t1) { store.releaseHeld(a, c5, 1) } shouldBe 0
        stateOf(t2, c5, 1) shouldBe "AWAITING_PRECEDING"
        inTx(t1) { store.releaseHeld(a, c2, 1) } shouldBe 1
        stateOf(t1, c2, 1) shouldBe "READY"
        inTx(t1) { store.releaseHeld(a, c1, 1) } shouldBe 1
        stateOf(t1, c1, 1) shouldBe "READY"
        inTx(t1) { store.releaseHeld(a, c1, 1) } shouldBe 0
        stateOf(t1, c1, 1) shouldBe "READY"
        inTx(t1) { store.heldBy(a, p1) } shouldBe emptyList()
        inTx(t1) { store.held(a, 10) } shouldBe listOf(hv(c3, p2))
        mine() shouldBe setOf(t1, t2)
        inTx(t2) { store.releaseHeld(b, c5, 1) } shouldBe 1
        stateOf(t2, c5, 1) shouldBe "READY"
        mine() shouldBe setOf(t1)
    }

    @Test
    fun `INV-AC-24517 held rejects a limit below 1`() {
        shouldThrow<IllegalArgumentException> { store.held(TenantId(t1), 0) }
    }

    private companion object {
        const val RUN_LEN = 8
    }
}
