package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.http.PendingRetryAfter
import com.dynokore.gst.reconciliation.adapters.http.ReconciliationController
import com.dynokore.gst.reconciliation.adapters.persistence.JooqCompletedRuns
import com.dynokore.gst.reconciliation.adapters.persistence.JooqReturnRecorded
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReader
import com.dynokore.gst.reconciliation.application.run.GetReconciliation
import com.dynokore.gst.reconciliation.application.run.ReturnRecorded
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.workpaper.AnnotateVariance
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Reconciliation
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import com.dynokore.platform.reconciliationengine.LineResult
import com.dynokore.platform.reconciliationengine.ReconciliationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * INV-AC-24420 (INV-T-24421, INV-REQ-657): the three states of a period over a real PostgreSQL schema; no fake
 * stands in for the run reader or the return-existence adapter.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class GetReconciliationEndpointTest : RunStoreDbFixture() {
    private val p1 = "P1"
    private val p2 = "P2"
    private val p3 = "P3"
    private val retryAfter = 7
    private val doc = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    private fun insertReturn(
        tenant: UUID,
        period: String,
        version: Int,
        form: String = "F5",
    ) = ReconciliationDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into reconciliation.gst_return (tenant_id, entity_ref, period_ref, version, form, filed_at, " +
                    "filing_acknowledgement, uploaded_by, recorded_at) " +
                    "values (?, ?, ?, ?, ?, now(), 'ACK', 'u', now())",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, ENTITY)
                it.setString(3, period)
                it.setInt(4, version)
                it.setString(5, form)
                it.executeUpdate()
            }
    }

    private fun recordRun(
        tenant: UUID,
        version: Int,
        at: String,
        lines: List<LineResult>,
    ) = asTenant(tenant) {
        session.runner.inTransaction {
            JooqCompletedRuns(session.dsl()).record(
                tenant.toString(),
                ENTITY,
                p1,
                completed(version, lines),
                Instant.parse(at),
            )
        }
    }

    private fun completed(
        version: Int,
        lines: List<LineResult>,
    ) = RunOutcome.Completed(
        returnVersion = version,
        result = ReconciliationResult(lines),
        unmappedLines = listOf(UnmappedLine(doc, 1, 3, "XX")),
        notReconcilable = emptyList(),
        raisesPer003 = false,
        reconcilingItems = emptyList(),
    )

    private fun controller(
        runs: RunReader =
            object : RunReader {
                override fun latest(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                ) = JooqRunReader(session.dsl()).latest(tenant, entityRef, periodRef)

                override fun page(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                    limit: Int,
                    after: com.dynokore.gst.reconciliation.application.run.RunCursor?,
                ) = JooqRunReader(session.dsl()).page(tenant, entityRef, periodRef, limit, after)

                override fun find(
                    tenant: String,
                    runId: String,
                ) = JooqRunReader(session.dsl()).find(tenant, runId)
            },
        returns: ReturnRecorded =
            ReturnRecorded { tenant, entity, period ->
                JooqReturnRecorded(session.dsl()).exists(tenant, entity, period)
            },
    ) = ReconciliationController(
        GetReconciliation(runs, returns),
        session.runner,
        session.tenants,
        PendingRetryAfter(retryAfter),
        AnnotateVariance(
            runs,
            LazyRunAnnotations { session.dsl() },
            LazyRunAttestations { session.dsl() },
            Clock.systemUTC(),
        ),
    )

    private fun read(
        tenant: UUID,
        period: String,
        controller: ReconciliationController = controller(),
    ): ResponseEntity<Reconciliation> = asTenant(tenant) { controller.getReconciliation(ENTITY, period) }

    private fun seed(): Pair<UUID, UUID> {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        insertReturn(t1, p1, 1)
        insertReturn(t1, p1, 2, "F7")
        insertReturn(t1, p2, 1)
        recordRun(t1, 1, "2026-04-28T09:10:00Z", secondLines)
        recordRun(t1, 2, "2026-04-29T09:10:00Z", listOf(box1()))
        insertReturn(t2, p1, 1)
        recordRun(t2, 1, "2026-04-30T09:10:00Z", listOf(box1("55.00")))
        return t1 to t2
    }

    private fun assertNoReturn(answer: ResponseEntity<Reconciliation>) {
        answer.statusCode.value() shouldBe 200
        val body = answer.body.shouldNotBeNull()
        body.boxes.shouldBeEmpty()
        body.unexplainedResidual.toPlainString() shouldBe "0.00"
        body.returnVersion.shouldBeNull()
        body.reasons.shouldNotBeNull() shouldHaveSize 1
        body.reasons!![0].code shouldBe "PER-008"
        body.reasons!![0].message.shouldNotBeBlank()
    }

    @Test
    fun `INV-AC-24420 a period with completed runs answers 200 with the latest run, scale-2 strings and no reasons`() {
        val (t1, _) = seed()
        val answer = read(t1, p1)
        answer.statusCode.value() shouldBe 200
        val body = answer.body.shouldNotBeNull()
        body.returnVersion shouldBe 2
        body.returnForm shouldBe Reconciliation.ReturnForm.F7
        body.reasons.shouldBeNull()
        body.boxes shouldHaveSize 1
        val box = body.boxes[0]
        box.box shouldBe "1"
        box.submittedAmount.toPlainString() shouldBe "100.00"
        box.filedAmount.toPlainString() shouldBe "90.00"
        box.variance.toPlainString() shouldBe "10.00"
        box.varianceClasses.map { it.varianceClass.value to it.amount.toPlainString() } shouldBe
            listOf("TIMING_DIFFERENCE" to "6.00", "UNEXPLAINED" to "4.00")
        body.unexplainedResidual.toPlainString() shouldBe "4.00"
        body.unmappedLines.shouldNotBeNull().map { it.docUuid.toString() } shouldBe listOf(doc)
    }

    @Test
    fun `INV-AC-24420 a recorded return and no completed run answers 503 PER-016 with Retry-After and no Box`() {
        val (t1, _) = seed()
        val answer = read(t1, p2)
        answer.statusCode.value() shouldBe 503
        answer.headers.contentType.toString() shouldBe "application/problem+json"
        answer.headers.getFirst("Retry-After") shouldBe "7"
        val envelope = (answer.body as Any) as ErrorEnvelope
        envelope.status shouldBe 503
        envelope.code shouldBe "PER-016"
        envelope.correlationId.shouldNotBeBlank()
    }

    @Test
    fun `INV-AC-24420 a period with no return answers 200 with PER-008 and no returnVersion`() {
        val (t1, _) = seed()
        assertNoReturn(read(t1, p3))
    }

    @Test
    fun `INV-AC-24420 another tenant with no return and no run gets PER-008, never the rows of the first tenant`() {
        val (_, t2) = seed()
        assertNoReturn(read(t2, p2))
        val own = read(t2, p1).body.shouldNotBeNull()
        own.boxes[0].submittedAmount.toPlainString() shouldBe "55.00"
    }

    @Test
    fun `INV-AC-24420 a failing run reader or return adapter is never answered as 200 or 503 PER-016`() {
        val (t1, _) = seed()
        val failingReader =
            object : RunReader {
                override fun latest(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                ) = error("run store down")

                override fun page(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                    limit: Int,
                    after: com.dynokore.gst.reconciliation.application.run.RunCursor?,
                ) = error("run store down")

                override fun find(
                    tenant: String,
                    runId: String,
                ) = error("run store down")
            }
        shouldThrow<IllegalStateException> { read(t1, p2, controller(runs = failingReader)) }
        shouldThrow<IllegalStateException> {
            read(t1, p3, controller(returns = ReturnRecorded { _, _, _ -> error("return table down") }))
        }
    }

    @Test
    fun `INV-AC-24420 the return-existence statement names the tenant in its WHERE, not only the bound tenant`() {
        val (t1, t2) = seed()
        val asked =
            asTenant(t1) {
                session.runner.inTransaction {
                    val recorded = JooqReturnRecorded(session.dsl())
                    listOf(
                        recorded.exists(t1.toString(), ENTITY, p2),
                        recorded.exists(t2.toString(), ENTITY, p2),
                        recorded.exists(t1.toString(), ENTITY, p3),
                    )
                }
            }
        asked shouldBe listOf(true, false, false)
    }
}
