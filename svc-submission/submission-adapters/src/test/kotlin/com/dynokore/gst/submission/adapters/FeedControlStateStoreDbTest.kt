package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqCompletenessRows
import com.dynokore.gst.submission.adapters.persistence.JooqFeedControlStateStore
import com.dynokore.gst.submission.adapters.persistence.JooqWindowPartStateStore
import com.dynokore.gst.submission.application.completeness.StoredRow
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The feed control state store and the completeness rows reader against the test database (INV-AC-22206). */
class FeedControlStateStoreDbTest {
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

    private val store = JooqFeedControlStateStore(dsl, runner)
    private val windows = JooqWindowPartStateStore(dsl, runner)
    private val reader = JooqCompletenessRows(dsl, runner)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    private fun record(
        tenant: UUID,
        failing: Boolean,
        version: Long,
        entity: String = "E",
        period: String = "P",
    ) = asTenant(tenant) { store.record(TenantId(tenant), "F1", entity, period, "COUNT", failing, version) }

    private fun rows(
        tenant: UUID,
        entity: String = "E",
        period: String = "P",
    ) = asTenant(tenant) { reader.rows(TenantId(tenant), entity, period) }

    @Test
    fun `INV-AC-22206 the feed row follows the highest stateVersion by one guarded statement`() {
        val t1 = UUID.randomUUID()
        record(t1, failing = true, version = 2) shouldBe true
        rows(t1) shouldBe listOf(StoredRow("feed:F1/COUNT", StoredState.FAILING))
        record(t1, failing = false, version = 1) shouldBe false
        rows(t1) shouldBe listOf(StoredRow("feed:F1/COUNT", StoredState.FAILING))
        record(t1, failing = false, version = 3) shouldBe true
        record(t1, failing = true, version = 0) shouldBe false
        record(t1, failing = true, version = 3) shouldBe false
        rows(t1) shouldBe listOf(StoredRow("feed:F1/COUNT", StoredState.NOT_FAILING))
    }

    @Test
    fun `INV-AC-22206 a versionless event is stored with version 0 only when no row exists, any version supersedes`() {
        val t1 = UUID.randomUUID()
        record(t1, failing = true, version = 0) shouldBe true
        record(t1, failing = false, version = 0) shouldBe false
        rows(t1) shouldBe listOf(StoredRow("feed:F1/COUNT", StoredState.FAILING))
        record(t1, failing = false, version = 1) shouldBe true
        rows(t1) shouldBe listOf(StoredRow("feed:F1/COUNT", StoredState.NOT_FAILING))
    }

    @Test
    fun `INV-AC-22206 the rows are tenant-isolated and another period or entity is not returned`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        record(t1, failing = false, version = 3)
        asTenant(t1) { windows.record(TenantId(t1), "E", "P", D1, D2, StoredState.NOT_FAILING, 2) } shouldBe true
        record(t1, failing = true, version = 1, entity = "E2")
        record(t1, failing = true, version = 1, period = "P2")
        rows(t1) shouldContainExactlyInAnyOrder
            listOf(
                StoredRow("feed:F1/COUNT", StoredState.NOT_FAILING),
                StoredRow("window:P", StoredState.NOT_FAILING),
            )
        rows(t2).shouldBeEmpty()
        record(t2, failing = true, version = 1) shouldBe true
        rows(t1).size shouldBe 2
        rows(t2) shouldBe listOf(StoredRow("feed:F1/COUNT", StoredState.FAILING))
    }

    private companion object {
        val D1: LocalDate = LocalDate.of(2026, 4, 1)
        val D2: LocalDate = LocalDate.of(2026, 6, 30)
    }
}
