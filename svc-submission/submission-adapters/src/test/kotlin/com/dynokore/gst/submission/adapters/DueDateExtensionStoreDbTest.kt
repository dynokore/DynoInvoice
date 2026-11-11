package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqDueDateExtensionStore
import com.dynokore.gst.submission.application.extension.DueDateExtension
import com.dynokore.gst.submission.application.extension.ExtensionScopeKind
import com.dynokore.gst.submission.application.extension.ExtensionTarget
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** The extension store against the test database as `submission_runtime` (INV-AC-20595). */
class DueDateExtensionStoreDbTest {
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
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                }
            }
        }

    private val store = JooqDueDateExtensionStore(dsl, runner)

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

    private fun row(
        event: String,
        original: LocalDate? = LocalDate.of(2026, 4, 30),
    ) = DueDateExtension(
        "E",
        "P",
        ExtensionTarget.BOTH,
        ExtensionScopeKind.DOCUMENT_SET,
        "a,b",
        original,
        LocalDate.of(2026, 5, 14),
        LocalDate.of(2026, 5, 7),
        "IRAS-1",
        event,
    )

    private fun insert(
        tenant: UUID,
        r: DueDateExtension,
    ) = asTenant(tenant) { store.insert(TenantId(tenant), r) }

    private fun read(tenant: UUID) = asTenant(tenant) { store.forPeriod(TenantId(tenant), "E", "P") }

    private fun sqlState(
        tenant: UUID,
        sql: String,
    ): String? =
        asTenant(tenant) {
            try {
                runner.inTransaction { dsl.execute(sql) }
                null
            } catch (e: Exception) {
                generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().first().sqlState
            }
        }

    @Test
    fun `INV-AC-20595 rows are stored and read back in order of recording`() {
        val a = UUID.randomUUID()
        insert(a, row("ev-1")) shouldBe true
        insert(a, row("ev-2", original = null)) shouldBe true
        insert(a, row("ev-0")) shouldBe true
        read(a).map { it.sourceEventId } shouldBe listOf("ev-1", "ev-2", "ev-0")
        read(a)[0] shouldBe row("ev-1")
        read(a)[1].originalDueDate shouldBe null
    }

    @Test
    fun `INV-AC-20595 the unique event id refuses a duplicate and keeps the first row`() {
        val a = UUID.randomUUID()
        insert(a, row("ev-1")) shouldBe true
        insert(a, row("ev-1").copy(irasReference = "OTHER")) shouldBe false
        read(a).single().irasReference shouldBe "IRAS-1"
    }

    @Test
    fun `INV-AC-20595 UPDATE and DELETE are refused to the runtime role`() {
        val a = UUID.randomUUID()
        insert(a, row("ev-1"))
        sqlState(a, "update submission.due_date_extension set iras_reference = 'X'") shouldBe "42501"
        sqlState(a, "delete from submission.due_date_extension") shouldBe "42501"
        read(a).single().irasReference shouldBe "IRAS-1"
    }

    @Test
    fun `INV-AC-20595 a second tenant reads nothing`() {
        val a = UUID.randomUUID()
        insert(a, row("ev-1"))
        read(UUID.randomUUID()).shouldBeEmpty()
        asTenant(a) { store.forPeriod(TenantId(UUID.randomUUID()), "E", "P") }.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-20595 the same event id of another tenant is a separate row`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insert(a, row("ev-1")) shouldBe true
        insert(b, row("ev-1")) shouldBe true
    }
}
