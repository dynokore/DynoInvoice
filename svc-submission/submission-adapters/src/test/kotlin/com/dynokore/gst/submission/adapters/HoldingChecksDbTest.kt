package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqHoldingChecks
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessCheckStore
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The holding-check read against `submission.readiness_check` as `submission_runtime`. */
class HoldingChecksDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private var bound: UUID = UUID.randomUUID()
    private val created = mutableListOf<UUID>()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val checks = JooqReadinessCheckStore(dsl, tenants)
    private val holding: HoldingChecks = JooqHoldingChecks(dsl, tenants)
    private val at = Instant.parse("2026-11-01T08:00:00Z")
    private val d1 = "11111111-1111-1111-1111-111111111111"
    private val d2 = "22222222-2222-2222-2222-222222222222"

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use { it.execute("delete from submission.readiness_check where tenant_id = '$t'") }
            }
        }
    }

    private fun <T> inTx(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { connection.commit() }
        } finally {
            connection.rollback()
        }
    }

    private fun append(
        t: UUID,
        outcome: String?,
        period: String = "P",
    ) = inTx(t) { checks.append("E", period, CheckVerdict.NOT_READY, at, "U", outcome) }

    private fun held(vararg docs: String) = """{"heldDocUuids": [${docs.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    fun `INV-AC-20556 the check that held a document is found and the latest holder wins`() {
        val t = UUID.randomUUID().also { created += it }
        val first = append(t, held(d1, d2))
        val second = append(t, held(d2))
        append(t, null)
        inTx(t) { holding.holdingCheckId("E", "P", d1) } shouldBe first.checkId
        inTx(t) { holding.holdingCheckId("E", "P", d2) } shouldBe second.checkId
    }

    @Test
    fun `INV-AC-20556 a look-alike uuid and an unheld uuid give null`() {
        val t = UUID.randomUUID().also { created += it }
        append(t, held(d1, d2))
        inTx(t) { holding.holdingCheckId("E", "P", d1.dropLast(1) + "2") } shouldBe null
        inTx(t) { holding.holdingCheckId("E", "P", d1.dropLast(1)) } shouldBe null
        inTx(t) { holding.holdingCheckId("E", "P", "33333333-3333-3333-3333-333333333333") } shouldBe null
        inTx(t) { holding.holdingCheckId("E", "P2", d1) } shouldBe null
        inTx(t) { holding.holdingCheckId("E2", "P", d1) } shouldBe null
        inTx(t) { holding.holdingCheckId("E", "P", "x\") || true || (@ == \"y") } shouldBe null
    }

    @Test
    fun `INV-AC-20556 a check of another tenant is never read`() {
        val t1 = UUID.randomUUID().also { created += it }
        val t2 = UUID.randomUUID().also { created += it }
        append(t2, held(d1))
        inTx(t1) { holding.holdingCheckId("E", "P", d1) } shouldBe null
    }
}
