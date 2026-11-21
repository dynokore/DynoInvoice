package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.application.completeness.fixedTenants
import com.dynokore.gst.submission.application.completeness.testEnvelope
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class UnreviewedActivationReadinessTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000032"))
    private val periodEnd = LocalDate.parse("2026-09-30")
    private val clock = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC)

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

    /** Returns every stored row of the tenant; the date filter is the lookup's job. */
    private class Rows : UnreviewedActivationSource {
        val stored = mutableListOf<Pair<TenantId, UnreviewedActivation>>()

        override fun upTo(
            tenant: TenantId,
            periodEnd: LocalDate,
        ): List<UnreviewedActivation> = stored.filter { it.first == tenant }.map { it.second }
    }

    private fun act(
        type: String,
        version: String,
        from: String,
    ) = UnreviewedActivation(type, version, LocalDate.parse(from))

    private val entries =
        listOf(
            ReadinessEntry("D1", listOf(LifecycleState.QUEUED), periodEnd, overridden = false),
            ReadinessEntry("D2", listOf(LifecycleState.HELD), periodEnd, overridden = false),
        )

    private fun runWith(
        rows: Rows?,
        recorder: Recorder = Recorder(),
    ): ReadinessRunResult.Determined {
        val lookup =
            rows?.let { UnreviewedActivationLookup(fixedTenants(tenant), { _, _, _ -> periodEnd }, it) }
        val useCase =
            RunReadiness(
                { _, _ -> entries },
                { _, _ -> ReadinessInputs(LocalDate.parse("2026-01-01"), CompletenessState.Passed) },
                recorder,
                clock,
                lookup,
            )
        return useCase.run("E", "P", "U").shouldBeInstanceOf<ReadinessRunResult.Determined>()
    }

    @Test
    fun `INV-AC-26295 an activation effective on the period end is listed`() {
        val rows = Rows().apply { stored += tenant to act("GST", "2", "2026-09-30") }
        runWith(rows).unreviewedActivations shouldBe listOf(act("GST", "2", "2026-09-30"))
    }

    @Test
    fun `INV-AC-26295 an activation effective one day after the period end is not listed`() {
        val rows = Rows().apply { stored += tenant to act("GST", "2", "2026-10-01") }
        runWith(rows).unreviewedActivations shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26295 an activation of another tenant is not listed`() {
        val rows = Rows().apply { stored += other to act("GST", "2", "2026-09-01") }
        runWith(rows).unreviewedActivations shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26295 the verdict, the stored check and the held outcome equal those of a run without activations`() {
        val rows = Rows().apply { stored += tenant to act("GST", "2", "2026-09-01") }
        val withRecorder = Recorder()
        val withoutRecorder = Recorder()
        val with = runWith(rows, withRecorder)
        val without = runWith(null, withoutRecorder)

        with.unreviewedActivations.size shouldBe 1
        without.unreviewedActivations shouldBe emptyList()
        with.verdict shouldBe without.verdict
        with.check shouldBe without.check
        withRecorder.appended.single().verdict shouldBe withoutRecorder.appended.single().verdict
        withRecorder.appended.single().outcomeValues shouldBe withoutRecorder.appended.single().outcomeValues
        withRecorder.appended.single().outcomeValues shouldBe """{"heldDocUuids":["D2"]}"""
    }

    @Test
    fun `INV-AC-26295 the list is ordered by effectiveFrom then packType then packVersion`() {
        val rows =
            Rows().apply {
                stored += tenant to act("B", "1", "2026-09-02")
                stored += tenant to act("B", "2", "2026-09-01")
                stored += tenant to act("B", "1", "2026-09-01")
                stored += tenant to act("A", "9", "2026-09-01")
            }
        runWith(rows).unreviewedActivations shouldBe
            listOf(
                act("A", "9", "2026-09-01"),
                act("B", "1", "2026-09-01"),
                act("B", "2", "2026-09-01"),
                act("B", "1", "2026-09-02"),
            )
    }

    private class Store : UnreviewedActivationStore {
        val rows = mutableSetOf<Pair<TenantId, UnreviewedActivation>>()

        override fun record(
            tenant: TenantId,
            activation: UnreviewedActivation,
        ): Boolean = rows.add(tenant to activation)
    }

    private fun readerFor(reviewed: Boolean) =
        UnreviewedActivationDataReader {
            if (reviewed) throw ContractViolation("reviewed must be false")
            UnreviewedActivationData(tenant, act("GST", "2", "2026-09-30"))
        }

    @Test
    fun `INV-AC-26295 the handler records the decoded activation once for two deliveries of the same event`() {
        val store = Store()
        val handler = UnreviewedActivationRecordedHandler(readerFor(false), store)
        val event = testEnvelope(tenant)
        handler.handle(event) shouldBe HandleOutcome.APPLIED
        handler.handle(event) shouldBe HandleOutcome.IGNORED
        store.rows shouldBe setOf(tenant to act("GST", "2", "2026-09-30"))
    }

    @Test
    fun `INV-AC-26295 the handler refuses a payload whose reviewed is true and stores nothing`() {
        val store = Store()
        val handler = UnreviewedActivationRecordedHandler(readerFor(true), store)
        shouldThrow<ContractViolation> { handler.handle(testEnvelope(tenant)) }
        store.rows shouldBe emptySet()
    }
}
