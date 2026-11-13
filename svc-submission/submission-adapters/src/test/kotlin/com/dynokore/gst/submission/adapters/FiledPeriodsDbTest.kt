package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.FilingFactFiledPeriods
import com.dynokore.gst.submission.adapters.persistence.JooqFilingFactStore
import com.dynokore.gst.submission.application.filing.NewFilingFact
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The filing reader over the filing fact store as `submission_runtime` (INV-AC-21093, INV-T-21094). */
class FiledPeriodsDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
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
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val clock = Clock.fixed(Instant.parse("2026-04-18T08:00:00Z"), ZoneOffset.UTC)
    private val store = JooqFilingFactStore(dsl, tenants, clock)
    private val filed = FilingFactFiledPeriods(store)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use { it.execute("delete from submission.filing_fact where tenant_id = '$t'") }
            }
        }
    }

    private fun <R> inTx(
        tenant: UUID,
        block: () -> R,
    ): R {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { connection.commit() }
        } finally {
            connection.rollback()
        }
    }

    @Test
    fun `INV-AC-21093 isFiled is true exactly for a period with a fact of the bound tenant`() {
        val a = UUID.randomUUID().also { created += it }
        val b = UUID.randomUUID().also { created += it }
        inTx(a) {
            store.append(NewFilingFact("E-001", "2026-Q1", LocalDate.of(2026, 4, 18), "A1", null, false, "u1"))
        }
        inTx(a) { filed.isFiled("E-001", "2026-Q1") } shouldBe true
        inTx(a) { filed.isFiled("E-001", "2026-Q2") } shouldBe false
        inTx(b) { filed.isFiled("E-001", "2026-Q1") } shouldBe false
    }
}
