package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqReadinessEntrySource
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.ReadinessCheckRecorder
import com.dynokore.gst.submission.application.readiness.ReadinessEntrySource
import com.dynokore.gst.submission.application.readiness.ReadinessInputs
import com.dynokore.gst.submission.application.readiness.ReadinessRunResult
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.application.readiness.RunReadiness
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.readiness.ReadinessResult
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The readiness entry source against the test database as `submission_runtime` (INV-AC-20479). */
class ReadinessEntrySourceDbTest {
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

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(checkNotNull(bound.get()))

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val day = LocalDate.of(2026, 6, 20)

    @AfterEach
    fun close() = connection.close()

    private class Doc(
        val uuid: String,
        val period: String,
        val states: List<String> = emptyList(),
        val reason: String? = null,
        val date: LocalDate? = LocalDate.of(2026, 6, 20),
        val inScope: Boolean = true,
    )

    private fun seed(
        tenant: UUID,
        entity: String,
        docs: List<Doc>,
    ) {
        bound.set(tenant)
        try {
            runner.inTransaction {
                docs.forEach { d ->
                    dsl.execute(
                        "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, " +
                            "period_ref, due_date, in_scope, kind, transaction_date, reason_code) " +
                            "values (?, ?, ?, ?, ?, ?, 'INVOICE', ?, ?)",
                        tenant,
                        d.uuid,
                        entity,
                        d.period,
                        LocalDate.of(2026, 11, 30),
                        d.inScope,
                        d.date,
                        d.reason,
                    )
                    d.states.forEachIndexed { i, state ->
                        dsl.execute(
                            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                                "values (?, ?, ?, ?, ?)",
                            tenant,
                            d.uuid,
                            i + 1,
                            state,
                            entity,
                        )
                    }
                }
            }
        } finally {
            bound.remove()
        }
    }

    private fun read(
        source: ReadinessEntrySource,
        tenant: UUID,
        entity: String,
        period: String,
    ): List<ReadinessEntry>? {
        bound.set(tenant)
        try {
            return runner.inTransaction { source.entries(entity, period) }
        } finally {
            bound.remove()
        }
    }

    private fun source(
        released: Set<String> = emptySet(),
        holder: Map<String, String> = emptyMap(),
    ) = JooqReadinessEntrySource(
        dsl,
        tenants,
        ReleasedOverrideChecks { _, _ -> released },
        HoldingChecks { _, _, doc -> holder[doc] },
    )

    private class World(
        val tenant: UUID,
        val entity: String,
        val period: String,
        val d: List<String>,
    )

    /** D1..D5 of the criterion in one entity and period of a fresh tenant. */
    private fun world(): World {
        val tenant = UUID.randomUUID()
        val entity = "E-${UUID.randomUUID()}".take(14)
        val period = "P-${UUID.randomUUID()}".take(14)
        val d = (1..5).map { UUID.randomUUID().toString() }
        seed(
            tenant,
            entity,
            listOf(
                Doc(d[0], period, listOf("ACKNOWLEDGED")),
                Doc(d[1], period, reason = "MDM-001"),
                Doc(d[2], period),
                Doc(d[3], period, inScope = false),
                Doc(d[4], period, listOf("QUEUED")),
            ),
        )
        return World(tenant, entity, period, d)
    }

    private fun byDoc(entries: List<ReadinessEntry>?) = checkNotNull(entries).associateBy { it.docUuid }

    @Test
    fun `INV-AC-20479 case 1 the transaction date is the column`() {
        val w = world()
        byDoc(read(source(), w.tenant, w.entity, w.period)).getValue(w.d[0]).transactionDate shouldBe day
    }

    @Test
    fun `INV-AC-20479 case 2 a version-less entry with a reason code is HELD with that code`() {
        val w = world()
        val e = byDoc(read(source(), w.tenant, w.entity, w.period)).getValue(w.d[1])
        e.versions shouldBe listOf(LifecycleState.HELD)
        e.reasonCode shouldBe "MDM-001"
    }

    @Test
    fun `INV-AC-20479 case 3 a version-less entry without a reason code is QUEUED`() {
        val w = world()
        val e = byDoc(read(source(), w.tenant, w.entity, w.period)).getValue(w.d[2])
        e.versions shouldBe listOf(LifecycleState.QUEUED)
        e.reasonCode shouldBe null
    }

    @Test
    fun `INV-AC-20479 case 4 a version-less entry out of scope is NOT_SUBMITTABLE`() {
        val w = world()
        byDoc(read(source(), w.tenant, w.entity, w.period)).getValue(w.d[3]).versions shouldBe
            listOf(LifecycleState.NOT_SUBMITTABLE)
    }

    @Test
    fun `INV-AC-20479 case 5 entries with version rows carry the stored states in version order`() {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(tenant, "E1", listOf(Doc(doc, "P1", listOf("REJECTED", "ACKNOWLEDGED", "SUPERSEDED"), reason = "X-1")))
        val e = byDoc(read(source(), tenant, "E1", "P1")).getValue(doc)
        e.versions shouldBe listOf(LifecycleState.REJECTED, LifecycleState.ACKNOWLEDGED, LifecycleState.SUPERSEDED)
        e.reasonCode shouldBe "X-1"
    }

    @Test
    fun `INV-AC-20479 case 6 overridden only when the holding check of the document is released`() {
        val w = world()
        val d1 = w.d[0]
        val d2 = w.d[1]
        val d3 = w.d[2]
        val d4 = w.d[3]
        val d5 = w.d[4]
        val src =
            source(
                released = setOf("chk-7", d3),
                holder = mapOf(d5 to "chk-7", d3 to "chk-8", d2 to "chk-17", d1 to "chk-70"),
            )
        val got = byDoc(read(src, w.tenant, w.entity, w.period))
        got.getValue(d5).overridden shouldBe true // holding check chk-7 is released
        got.getValue(d3).overridden shouldBe false // holding check chk-8 is only pending; uuid match does not count
        got.getValue(d2).overridden shouldBe false // look-alike chk-17 of the released chk-7
        got.getValue(d1).overridden shouldBe false // look-alike chk-70
        got.getValue(d4).overridden shouldBe false // no holding check
        val noHolder = byDoc(read(source(released = setOf(d4, d5)), w.tenant, w.entity, w.period))
        noHolder.values.none { it.overridden } shouldBe true
    }

    @Test
    fun `INV-AC-20479 case 7 an undated in-scope entry makes the period unavailable and an empty period is empty`() {
        val tenant = UUID.randomUUID()
        val p = "P-undated"
        seed(
            tenant,
            "E1",
            listOf(Doc(UUID.randomUUID().toString(), p), Doc(UUID.randomUUID().toString(), p, date = null)),
        )
        read(source(), tenant, "E1", p) shouldBe null
        read(source(), tenant, "E1", "P-empty") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20479 the criterion read returns the five entries of the bound tenant only`() {
        val w = world()
        val other = UUID.randomUUID()
        seed(other, w.entity, listOf(Doc(UUID.randomUUID().toString(), w.period)))
        val got = read(source(), w.tenant, w.entity, w.period)
        checkNotNull(got).map { it.docUuid }.toSet() shouldBe w.d.toSet()
        got.size shouldBe 5
        read(source(), other, w.entity, w.period)!!.size shouldBe 1
    }

    @Test
    fun `INV-AC-20479 RunReadiness over this source counts the held MDM-001 document as unsubmitted`() {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        seed(tenant, "E1", listOf(Doc(doc, "P1", reason = "MDM-001")))
        val recorder =
            object : ReadinessCheckRecorder {
                override fun append(
                    entityRef: String,
                    periodRef: String,
                    verdict: CheckVerdict,
                    checkedAt: Instant,
                    approver: String,
                    outcomeValues: String?,
                ) = StoredReadinessCheck(
                    1L,
                    StoredReadinessCheck.checkIdOf(1L),
                    entityRef,
                    periodRef,
                    verdict,
                    checkedAt,
                    approver,
                    outcomeValues,
                )
            }
        val clock = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC)
        val run = RunReadiness(source(), { _, _ -> ReadinessInputs(null, CompletenessState.Passed) }, recorder, clock)
        bound.set(tenant)
        val result =
            try {
                runner.inTransaction { run.run("E1", "P1", "U") }
            } finally {
                bound.remove()
            }
        val blocked =
            result
                .shouldBeInstanceOf<ReadinessRunResult.Determined>()
                .result
                .shouldBeInstanceOf<ReadinessResult.Blocked>()
        blocked.counts.unsubmitted shouldBe 1
        blocked.residual.single().docUuid shouldBe doc
        blocked.residual.single().reasonCode shouldBe "MDM-001"
    }

    /** Compile-time conformance: the three ports are implemented and called through their port types. */
    private object Conforming : ReadinessEntrySource, ReleasedOverrideChecks, HoldingChecks {
        override fun entries(
            entityRef: String,
            periodRef: String,
        ): List<ReadinessEntry>? = emptyList()

        override fun releasedCheckIds(
            entityRef: String,
            periodRef: String,
        ): Set<String> = setOf("chk-1")

        override fun holdingCheckId(
            entityRef: String,
            periodRef: String,
            docUuid: String,
        ): String? = null
    }

    @Test
    fun `INV-AC-20479 the ports are callable through their port types`() {
        val entries: ReadinessEntrySource = Conforming
        val released: ReleasedOverrideChecks = Conforming
        val holding: HoldingChecks = Conforming
        entries.entries("E", "P") shouldBe emptyList()
        released.releasedCheckIds("E", "P") shouldBe setOf("chk-1")
        holding.holdingCheckId("E", "P", "d") shouldBe null
    }
}
