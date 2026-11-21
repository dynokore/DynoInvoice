package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.completeness.StoredRow
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.gst.submission.application.startdate.ObligationStartDates
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.BlockCode
import com.dynokore.gst.submission.domain.readiness.PeriodVerdict
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** A stalled feed row makes the period NOT_READY through the production input source (INV-REQ-1071). */
class StalledFeedReadinessTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000084"))
    private val start = LocalDate.of(2026, 4, 1)
    private val now = Instant.parse("2026-11-12T08:00:00Z")
    private val day = LocalDate.parse("2026-10-15")

    private class Tenants(
        val tenant: TenantId,
    ) : TenantContext {
        override fun current(): TenantId = tenant

        override fun currentActor(): String? = null

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }

    private class StartDates : ObligationStartDates {
        val dates = mutableMapOf<Pair<TenantId, String>, LocalDate>()

        override fun record(
            tenant: TenantId,
            entityRef: String,
            startDate: LocalDate,
            effectiveDate: LocalDate,
        ): Boolean {
            dates[tenant to entityRef] = startDate
            return true
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
        ): LocalDate? = dates[tenant to entityRef]
    }

    private class Rows : CompletenessRows {
        val stored = mutableMapOf<Triple<TenantId, String, String>, List<StoredRow>>()

        override fun rows(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ): List<StoredRow> = stored[Triple(tenant, entityRef, period)].orEmpty()
    }

    private class Recorder : ReadinessCheckRecorder {
        val appended = mutableListOf<StoredReadinessCheck>()

        override fun append(
            entityRef: String,
            periodRef: String,
            verdict: CheckVerdict,
            checkedAt: Instant,
            approver: String,
            outcomeValues: String?,
        ): StoredReadinessCheck =
            StoredReadinessCheck(
                appended.size + 1L,
                StoredReadinessCheck.checkIdOf(appended.size + 1L),
                entityRef,
                periodRef,
                verdict,
                checkedAt,
                approver,
                outcomeValues,
            ).also { appended += it }
    }

    private val dates = StartDates()
    private val rows = Rows()
    private val recorder = Recorder()
    private val source = StoredReadinessInputSource(Tenants(tenant), dates, rows)

    private fun run(): ReadinessRunResult {
        val entries = listOf(ReadinessEntry("D1", listOf(LifecycleState.ACKNOWLEDGED), day, overridden = false))
        return RunReadiness(
            { _, _ -> entries },
            { entity, period -> source.inputs(entity, period) },
            recorder,
            Clock.fixed(now, ZoneOffset.UTC),
        ).run("E", "P", "U")
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength") // the ruled case name is one line
    fun `INV-AC-26134 a stalled feed row makes the period NOT_READY with PER-002 although every document is acknowledged`() {
        dates.record(tenant, "E", start, start)
        rows.stored[Triple(tenant, "E", "P")] = listOf(StoredRow("feed:F1/COUNT", StoredState.FAILING))

        val determined = run().shouldBeInstanceOf<ReadinessRunResult.Determined>()
        val verdict = determined.verdict.shouldBeInstanceOf<PeriodVerdict.NotReady>()
        verdict.codes shouldBe setOf(BlockCode.PER_002)
        verdict.failedControls shouldBe listOf("feed:F1/COUNT")
        recorder.appended.size shouldBe 1
        recorder.appended.single().verdict shouldBe CheckVerdict.NOT_READY
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength") // the ruled case name is one line
    fun `INV-AC-26134 the same period with the row NOT_FAILING is READY and a period with no completeness row is not READY`() {
        dates.record(tenant, "E", start, start)
        rows.stored[Triple(tenant, "E", "P")] = listOf(StoredRow("feed:F1/COUNT", StoredState.NOT_FAILING))
        val passing = run().shouldBeInstanceOf<ReadinessRunResult.Determined>()
        passing.verdict shouldBe PeriodVerdict.Ready
        recorder.appended.map { it.verdict } shouldBe listOf(CheckVerdict.READY)

        rows.stored.clear()
        recorder.appended.clear()
        run() shouldBe ReadinessRunResult.Undetermined
        recorder.appended.isEmpty() shouldBe true
    }
}
