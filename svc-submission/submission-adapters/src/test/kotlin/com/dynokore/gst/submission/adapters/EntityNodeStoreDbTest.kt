package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqEntityNodeStore
import com.dynokore.gst.submission.application.gstn.EntityNode
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The entity node store against the test database as `submission_runtime` (INV-AC-22283). */
class EntityNodeStoreDbTest {
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

    private val store = JooqEntityNodeStore(dsl, runner)

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

    private fun node(
        version: Long,
        gstn: String? = "G$version",
    ) = EntityNode("ENT-1", "ENTITY", "GRP-1", gstn, version)

    private fun record(
        tenant: UUID,
        node: EntityNode,
    ) = asTenant(tenant) { store.record(TenantId(tenant), node) }

    private fun find(tenant: UUID) = asTenant(tenant) { store.find(TenantId(tenant), "ENT-1") }

    @Test
    fun `INV-AC-22283 record and find round trip`() {
        val a = UUID.randomUUID()
        find(a) shouldBe null
        record(a, node(1)) shouldBe true
        find(a) shouldBe node(1)
        record(a, EntityNode("GRP-1", "GROUP", null, null, 5)) shouldBe true
        asTenant(a) { store.find(TenantId(a), "GRP-1") } shouldBe EntityNode("GRP-1", "GROUP", null, null, 5)
    }

    @Test
    fun `INV-AC-22283 a strictly higher version replaces and an equal or lower one changes nothing`() {
        val a = UUID.randomUUID()
        record(a, node(5)) shouldBe true
        record(a, node(6, "NEW")) shouldBe true
        find(a) shouldBe node(6, "NEW")
        val stored = find(a)
        record(a, node(6, "OTHER")) shouldBe false
        find(a) shouldBe stored
        record(a, node(3, "OLD")) shouldBe false
        find(a) shouldBe stored
    }

    @Test
    fun `INV-AC-22283 a second tenant finds no node and cannot overwrite it`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        record(a, node(4)) shouldBe true
        find(b) shouldBe null
        record(b, node(2, "B-OWN")) shouldBe true
        find(a) shouldBe node(4)
        find(b) shouldBe node(2, "B-OWN")
        shouldThrow<DataAccessException> { asTenant(b) { store.record(TenantId(a), node(9)) } }
        find(a) shouldBe node(4)
    }

    @Test
    fun `INV-AC-22283 a delete by the runtime role fails with a permission error`() {
        val a = UUID.randomUUID()
        record(a, node(1))
        asTenant(a) {
            SignedBindings.bind(connection, a)
            val e = shouldThrow<DataAccessException> { dsl.execute("delete from submission.entity_node") }
            connection.rollback()
            e.message!!.contains("permission denied") shouldBe true
        }
        find(a) shouldBe node(1)
    }
}
