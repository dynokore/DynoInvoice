package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqMeasuredTenantSource
import com.dynokore.gst.submission.adapters.persistence.JooqUnsubmittedCountsByEntity
import com.dynokore.gst.submission.application.offboarding.MeasureUnsubmittedCount
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
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

/** The measurement reader and the measured tenant source against the test database as `submission_runtime`. */
class UnsubmittedCountMeasurementDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                bound.get()?.let { SignedBindings.bind(connection, it) }
                return block().also { connection.commit() }
            }
        }

    private val instant = Instant.parse("2026-11-19T10:00:00Z")
    private val useCase =
        MeasureUnsubmittedCount(JooqUnsubmittedCountsByEntity(dsl, runner), Clock.fixed(instant, ZoneOffset.UTC))
    private val reader = JooqUnsubmittedCountsByEntity(dsl, runner)

    private val platform =
        object : PlatformTenantBinding {
            override fun <T> bindPlatform(
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val tenantSource = JooqMeasuredTenantSource(dsl, platform, WorkloadPrincipal("submission-svc"), runner)

    @AfterEach
    fun close() = connection.close()

    private fun <T> bindTo(
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

    private fun seed(
        tenant: UUID,
        entity: String,
        doc: String,
        inScope: Boolean,
        vararg states: String,
    ) = bindTo(tenant) {
        runner.inTransaction {
            dsl.execute(
                "insert into submission.obligation_register_entry " +
                    "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind) " +
                    "values (?, ?, ?, ?, ?, ?, ?)",
                tenant,
                doc,
                entity,
                "p1",
                LocalDate.of(2026, 11, 30),
                inScope,
                "INVOICE",
            )
            states.forEachIndexed { index, state ->
                dsl.execute(
                    "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                        "values (?, ?, ?, ?, ?)",
                    tenant,
                    doc,
                    index + 1,
                    state,
                    entity,
                )
            }
        }
    }

    private fun node(
        tenant: UUID,
        ref: String,
    ) = bindTo(tenant) {
        runner.inTransaction {
            dsl.execute(
                "insert into submission.entity_node (tenant_id, node_ref, kind, structure_version) " +
                    "values (?, ?, 'ENTITY', 1)",
                tenant,
                ref,
            )
        }
    }

    private class Fixture(
        val t1: UUID = UUID.randomUUID(),
        val t2: UUID = UUID.randomUUID(),
        val t3: UUID = UUID.randomUUID(),
        val t4: UUID = UUID.randomUUID(),
        val s: String = UUID.randomUUID().toString().take(8),
    ) {
        val e1 = "E1-$s"
        val e2 = "E2-$s"
        val e3 = "E3-$s"
    }

    private fun fixture(): Fixture {
        val f = Fixture()
        val s = f.s
        seed(f.t1, f.e1, "D1-$s", true, "ACKNOWLEDGED")
        seed(f.t1, f.e1, "D2-$s", true, "QUEUED")
        seed(f.t1, f.e1, "D3-$s", true, "SENT")
        seed(f.t1, f.e1, "D4-$s", true, "REJECTED")
        seed(f.t1, f.e1, "D5-$s", true, "ACKNOWLEDGED", "REJECTED")
        seed(f.t1, f.e1, "D6-$s", true, "CANCELLED")
        seed(f.t1, f.e1, "D7-$s", false, "QUEUED")
        seed(f.t1, f.e2, "D8-$s", true, "QUEUED")
        seed(f.t1, f.e2, "D10-$s", true)
        seed(f.t1, "E4-$s", "D11-$s", true, "ACKNOWLEDGED")
        seed(f.t2, f.e3, "D9-$s", true, "QUEUED")
        node(f.t3, "N3-$s")
        return f
    }

    private fun counts(
        bindTo: UUID,
        tenant: UUID,
    ): Map<String, Int> = bindTo(bindTo) { reader.counts(TenantId(tenant)) }

    @Test
    fun `INV-AC-22871 counts by entity equal the unsubmitted in-scope documents and the measurement totals them`() {
        val f = fixture()
        counts(f.t1, f.t1) shouldBe mapOf(f.e1 to 3, f.e2 to 2)
        counts(f.t2, f.t2) shouldBe mapOf(f.e3 to 1)
        counts(f.t3, f.t3) shouldBe emptyMap()
        val m = bindTo(f.t1) { useCase.execute(TenantId(f.t1)) }
        m.total shouldBe 5
        m.measuredAt shouldBe instant
        m.entities.map { it.entityRef to it.count } shouldBe listOf(f.e1 to 3, f.e2 to 2)
    }

    @Test
    fun `INV-AC-22871 the reader bound to T2 and asked for T1 answers the empty map`() {
        val f = fixture()
        counts(f.t2, f.t1) shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-22871 a failed read throws the same exception and never answers an empty map`() {
        val boom = IllegalStateException("db down")
        val failing =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = throw boom
            }
        val thrown =
            runCatching { JooqUnsubmittedCountsByEntity(dsl, failing).counts(TenantId(UUID.randomUUID())) }
                .exceptionOrNull()
        (thrown === boom) shouldBe true
    }

    @Test
    fun `INV-AC-22871 the measured tenants hold T1 T2 and T3 and not T4`() {
        val f = fixture()
        val seen = tenantSource.tenants().map { it.value }.toSet()
        seen.intersect(setOf(f.t1, f.t2, f.t3, f.t4)) shouldBe setOf(f.t1, f.t2, f.t3)
    }
}
