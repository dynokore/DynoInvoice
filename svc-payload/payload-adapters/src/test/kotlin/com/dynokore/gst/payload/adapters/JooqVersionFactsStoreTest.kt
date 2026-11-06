package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.domain.version.VersionFacts
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The version facts store against the real database as `payload_runtime` with a signed binding (INV-AC-17179). */
class JooqVersionFactsStoreTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val doc = UUID.randomUUID()

    private fun <T> bound(
        tenant: UUID,
        signature: String? = null,
        block: (JooqVersionFactsStore) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            bind(c, tenant, signature)
            try {
                block(JooqVersionFactsStore(DSL.using(c, SQLDialect.POSTGRES)) { tenant })
            } finally {
                c.commit()
            }
        }

    private fun bind(
        c: Connection,
        tenant: UUID,
        signature: String?,
    ) = if (signature == null) SignedBindings.bind(c, tenant) else SignedBindings.bind(c, tenant, signature = signature)

    private fun record(
        tenant: UUID,
        facts: VersionFacts,
        version: Int = 1,
    ) = bound(tenant) { it.record(doc, version, facts) }

    private fun find(
        tenant: UUID,
        version: Int = 1,
    ) = bound(tenant) { it.find(doc, version) }

    @Test
    fun `record then find returns the same entity and total`() {
        record(t1, VersionFacts("E-1", BigDecimal("1000.00"))) shouldBe true
        find(t1) shouldBe VersionFacts("E-1", BigDecimal("1000.00"))
    }

    @Test
    fun `a second record of the same key returns false and find returns the first`() {
        record(t1, VersionFacts("E-1", BigDecimal("1000.00"))) shouldBe true
        record(t1, VersionFacts("E-2", BigDecimal("5.00"))) shouldBe false
        find(t1) shouldBe VersionFacts("E-1", BigDecimal("1000.00"))
    }

    @Test
    fun `the table refuses version 0`() {
        shouldThrow<DataAccessException> { record(t1, VersionFacts("E-1", null), version = 0) }
    }

    @Test
    fun `a session bound to another tenant or with a forged binding finds nothing`() {
        record(t1, VersionFacts("E-1", BigDecimal("1000.00")))
        find(t2) shouldBe null
        val forged = SignedBindings.signed(t2)
        // a signature of tenant t2 presented for tenant t1 is forged
        val result = runCatching { bound(t1, signature = forged) { it.find(doc, 1) } }
        result.getOrNull() shouldBe null
    }

    @Test
    fun `a row with a null total is found as null`() {
        record(t1, VersionFacts("E-1", null), version = 2) shouldBe true
        find(t1, 2) shouldBe null
        record(t1, VersionFacts("E-1", BigDecimal("1.00")), version = 2) shouldBe false // the row exists
    }

    @Test
    fun `two simultaneous records of the same key leave one row and exactly one true`() {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs =
                List(2) { n ->
                    pool.submit<Boolean> {
                        bound(t1) { s ->
                            barrier.await(30, TimeUnit.SECONDS)
                            s.record(doc, 1, VersionFacts("E-$n", BigDecimal("1.00")))
                        }
                    }
                }
            jobs.map { it.get(60, TimeUnit.SECONDS) }.count { it } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
        (find(t1) != null) shouldBe true
    }
}
