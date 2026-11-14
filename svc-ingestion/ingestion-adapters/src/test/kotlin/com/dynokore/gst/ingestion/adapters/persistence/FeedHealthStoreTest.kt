package com.dynokore.gst.ingestion.adapters.persistence

import com.dynokore.gst.ingestion.adapters.IngestionDb
import com.dynokore.gst.ingestion.adapters.SignedBindings
import com.dynokore.gst.ingestion.application.feedhealth.FeedControl
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthKey
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * INV-AC-22215 (INV-REQ-815): the stored counter is per tenant, feed, entityRef, period and control, rises by one per
 * save, rolls back with the transaction, and the table is tenant-isolated. A real PostgreSQL database migrated by this
 * module's migrations, the jOOQ store, over the `ingestion_runtime` login.
 */
class FeedHealthStoreTest {
    private fun key(
        tenant: UUID,
        control: FeedControl,
    ) = FeedHealthKey(tenant, "F1", "E", "P", control)

    /** One transaction of the runtime login bound to [tenant]: committed when [block] returns, else rolled back. */
    private fun <T> inTransaction(
        tenant: UUID,
        block: (DSLContext, FeedHealthJooqStore) -> T,
    ): T =
        IngestionDb.connect(IngestionDb.RUNTIME, IngestionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            try {
                SignedBindings.bind(connection, tenant)
                val dsl = DSL.using(connection, SQLDialect.POSTGRES)
                val result = block(dsl, FeedHealthJooqStore(dsl))
                connection.commit()
                result
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

    private fun save(
        key: FeedHealthKey,
        state: FeedHealthState,
    ): Long = inTransaction(key.tenantId) { _, store -> store.save(key, state) }

    private fun count(sql: String): Int =
        IngestionDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun rows(tenant: UUID): Int = count("select count(*) from ingestion.feed_health_state where tenant_id = '$tenant'")

    @Test
    fun `INV-AC-22215 the counter is 1 then 2 for one key, restarts at 1 for another control and for another tenant`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val saves =
            listOf(
                save(key(t1, FeedControl.COUNT), FeedHealthState.FAILED),
                save(key(t1, FeedControl.COUNT), FeedHealthState.CLEARED),
                save(key(t1, FeedControl.VALUE), FeedHealthState.FAILED),
                save(key(t2, FeedControl.COUNT), FeedHealthState.FAILED),
            )
        saves shouldBe listOf(1L, 2L, 1L, 1L)
        rows(t1) shouldBe 2
        rows(t2) shouldBe 1
    }

    @Test
    fun `INV-AC-22215 a rolled-back save leaves no row and the next save returns the value it would have returned`() {
        val tenant = UUID.randomUUID()
        val key = key(tenant, FeedControl.SEQUENCE)
        shouldThrow<IllegalStateException> {
            inTransaction(tenant) { _, store ->
                store.save(key, FeedHealthState.FAILED) shouldBe 1L
                error("failure before commit")
            }
        }
        rows(tenant) shouldBe 0
        save(key, FeedHealthState.FAILED) shouldBe 1L
        shouldThrow<IllegalStateException> {
            inTransaction(tenant) { _, store ->
                store.save(key, FeedHealthState.CLEARED) shouldBe 2L
                error("failure before commit")
            }
        }
        save(key, FeedHealthState.CLEARED) shouldBe 2L
    }

    @Test
    fun `INV-AC-22215 current returns the stored state for the right tenant only`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        inTransaction(t1) { _, store -> store.current(key(t1, FeedControl.COUNT)) shouldBe null }
        save(key(t1, FeedControl.COUNT), FeedHealthState.FAILED)
        inTransaction(t1) { _, store -> store.current(key(t1, FeedControl.COUNT)) shouldBe FeedHealthState.FAILED }
        save(key(t1, FeedControl.COUNT), FeedHealthState.CLEARED)
        inTransaction(t1) { _, store -> store.current(key(t1, FeedControl.COUNT)) shouldBe FeedHealthState.CLEARED }
        inTransaction(t1) { _, store -> store.current(key(t1, FeedControl.VALUE)) shouldBe null }
        inTransaction(t2) { _, store -> store.current(key(t1, FeedControl.COUNT)) shouldBe null }
    }

    @Test
    fun `INV-AC-22215 tenant B cannot save a row with the tenant id of tenant A`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        shouldThrow<org.jooq.exception.DataAccessException> {
            inTransaction(b) { _, store -> store.save(key(a, FeedControl.COUNT), FeedHealthState.FAILED) }
        }
        rows(a) shouldBe 0
    }

    @Test
    fun `INV-AC-22215 the runtime role holds select, insert and update only and a delete is refused`() {
        val tenant = UUID.randomUUID()
        save(key(tenant, FeedControl.MANIFEST), FeedHealthState.FAILED)
        IngestionDb.connect(IngestionDb.RUNTIME, IngestionDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            shouldThrow<SQLException> {
                connection.createStatement().use { it.executeUpdate("delete from ingestion.feed_health_state") }
            }.sqlState shouldBe "42501"
            connection.rollback()
        }
        rows(tenant) shouldBe 1
    }
}
