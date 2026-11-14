package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqCompletenessRows
import com.dynokore.gst.submission.adapters.persistence.JooqWindowPartStateStore
import com.dynokore.gst.submission.application.completeness.StoredRow
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The window part state store against the test database (INV-AC-22206). */
class WindowPartStateStoreDbTest {
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

    private val store = JooqWindowPartStateStore(dsl, runner)
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
        state: StoredState,
        version: Long,
        to: LocalDate = D2,
    ) = asTenant(tenant) { store.record(TenantId(tenant), "E", "P", D1, to, state, version) }

    private fun rows(tenant: UUID) = asTenant(tenant) { reader.rows(TenantId(tenant), "E", "P") }

    @Test
    fun `INV-AC-22206 the window row follows the highest stateVersion by one guarded statement`() {
        val t1 = UUID.randomUUID()
        record(t1, StoredState.FAILING, 1) shouldBe true
        rows(t1) shouldBe listOf(StoredRow("window:P", StoredState.FAILING))
        record(t1, StoredState.NOT_FAILING, 2) shouldBe true
        record(t1, StoredState.OPEN, 0) shouldBe false
        record(t1, StoredState.OPEN, 2) shouldBe false
        rows(t1) shouldBe listOf(StoredRow("window:P", StoredState.NOT_FAILING))
    }

    @Test
    fun `INV-AC-22206 a versionless OPEN is stored with version 0 and a version 1 event supersedes it`() {
        val t1 = UUID.randomUUID()
        record(t1, StoredState.OPEN, 0) shouldBe true
        rows(t1) shouldBe listOf(StoredRow("window:P", StoredState.OPEN))
        record(t1, StoredState.FAILING, 1) shouldBe true
        rows(t1) shouldBe listOf(StoredRow("window:P", StoredState.FAILING))
    }

    @Test
    fun `INV-AC-22206 the window rows are tenant-isolated and a part with other bounds is its own row`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        record(t1, StoredState.FAILING, 1)
        rows(t2).shouldBeEmpty()
        record(t2, StoredState.NOT_FAILING, 1) shouldBe true
        rows(t1) shouldBe listOf(StoredRow("window:P", StoredState.FAILING))
        record(t1, StoredState.OPEN, 1, to = LocalDate.of(2026, 7, 31)) shouldBe true
        rows(t1).size shouldBe 2
    }

    private companion object {
        val D1: LocalDate = LocalDate.of(2026, 4, 1)
        val D2: LocalDate = LocalDate.of(2026, 6, 30)
    }
}
