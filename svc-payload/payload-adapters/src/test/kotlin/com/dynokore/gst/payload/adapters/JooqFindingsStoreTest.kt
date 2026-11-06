package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqFindingsStore
import com.dynokore.gst.payload.application.finding.RecordValidationFindings
import com.dynokore.gst.payload.domain.finding.ValidationReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The findings store against the real database as `payload_runtime` with a signed binding (INV-AC-17037). */
class JooqFindingsStoreTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val doc = UUID.randomUUID()

    private fun reason(
        code: String,
        severity: String,
        overridable: Boolean?,
        field: String? = null,
    ) = ValidationReason(code, field, "m $code", severity, overridable)

    private fun <T> bound(
        tenant: UUID,
        commit: Boolean = true,
        signature: String? = null,
        block: (JooqFindingsStore) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            bind(c, tenant, signature)
            try {
                block(JooqFindingsStore(DSL.using(c, SQLDialect.POSTGRES)) { tenant })
            } finally {
                if (commit) c.commit() else c.rollback()
            }
        }

    private fun bind(
        c: Connection,
        tenant: UUID,
        signature: String?,
    ) = if (signature == null) SignedBindings.bind(c, tenant) else SignedBindings.bind(c, tenant, signature = signature)

    private fun record(
        tenant: UUID,
        version: Int,
        vararg reasons: ValidationReason,
    ) = bound(tenant) { RecordValidationFindings(it).record(doc, version, reasons.toList()) }

    private fun read(
        tenant: UUID,
        version: Int,
    ) = bound(tenant) { s -> s.blocking(doc, version).map { it.code to it.overridable } }

    private val pay011 get() = reason("PAY-011", "BLOCK", true)
    private val pay001 get() = reason("PAY-001", "BLOCK", false)
    private val pay004 get() = reason("PAY-004", "WARN", true)

    @Test
    fun `INV-AC-17037 findings are stored per version and tenant and replaced as a whole`() {
        record(t1, 1, pay011, pay001, pay004)
        read(t1, 1).toSet() shouldBe setOf("PAY-011" to true, "PAY-001" to false)
        read(t1, 2) shouldBe emptyList()
        read(t2, 1) shouldBe emptyList()
        record(t1, 1, pay011)
        read(t1, 1).toSet() shouldBe setOf("PAY-011" to true)
        shouldThrow<IllegalArgumentException> {
            record(t1, 1, pay001, reason("PAY-004", "block", true))
        }
        shouldThrow<IllegalArgumentException> { record(t1, 1, pay001, reason("PAY-004", "BLOCK ", true)) }
        shouldThrow<IllegalArgumentException> { record(t1, 1, pay001, reason("PAY-004", "FATAL", true)) }
        read(t1, 1).toSet() shouldBe setOf("PAY-011" to true)
    }

    @Test
    fun `version 1 is read back in id order and version 2 is empty`() {
        record(t1, 1, pay011, pay001)
        read(t1, 1) shouldBe listOf("PAY-011" to true, "PAY-001" to false)
        read(t1, 2) shouldBe emptyList()
    }

    @Test
    fun `two reasons of one code and field store one row not overridable when one is false`() {
        record(t1, 1, reason("PAY-011", "BLOCK", true, "x"), reason("PAY-011", "BLOCK", false, "x"))
        read(t1, 1) shouldBe listOf("PAY-011" to false)
    }

    @Test
    fun `a second recording of version 1 leaves PAY-011 only`() {
        record(t1, 1, pay011, pay001)
        record(t1, 1, pay011)
        read(t1, 1) shouldBe listOf("PAY-011" to true)
    }

    @Test
    fun `a session bound to another tenant or with a forged binding reads nothing`() {
        record(t1, 1, pay011)
        read(t2, 1) shouldBe emptyList()
        val forged = SignedBindings.signed(t2)
        // a signature of tenant t2 presented for tenant t1 is forged
        val result = runCatching { bound(t1, signature = forged) { it.blocking(doc, 1) } }
        result.getOrNull().orEmpty() shouldBe emptyList()
    }

    @Test
    fun `the same set recorded twice leaves one row per code and field`() {
        record(t1, 1, pay011, pay001)
        record(t1, 1, pay011, pay001)
        read(t1, 1).size shouldBe 2
    }

    @Test
    fun `two simultaneous recordings of the same set leave one row per code and field`() {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs =
                List(2) {
                    pool.submit {
                        bound(t1) { s ->
                            barrier.await(30, TimeUnit.SECONDS)
                            RecordValidationFindings(s).record(doc, 1, listOf(pay011, pay001))
                        }
                    }
                }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        read(t1, 1).size shouldBe 2
    }

    @Test
    fun `a rollback of the callers transaction leaves the previous findings`() {
        record(t1, 1, pay011)
        bound(t1, commit = false) { RecordValidationFindings(it).record(doc, 1, listOf(pay001)) }
        read(t1, 1) shouldBe listOf("PAY-011" to true)
    }
}
