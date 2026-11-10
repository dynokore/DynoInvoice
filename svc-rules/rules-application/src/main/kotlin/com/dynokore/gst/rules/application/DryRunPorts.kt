package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The window of a dry run: first period reference, last period reference and number of periods (taken as given). */
data class DryRunWindow(
    val firstPeriodRef: String,
    val lastPeriodRef: String,
    val periodCount: Int,
)

/**
 * One page of the sample (`PackDryRunSampleRecorded`). The items are the raw members of the page, so that a page that
 * breaks the contract reaches the use case, which refuses it, instead of failing inside an adapter.
 */
data class SamplePage(
    val number: Int,
    val lastPage: Boolean,
    val documents: List<Map<String, Any?>>,
)

/** The pages of the sample of one dry run. The use case reads the sample only through this port. */
interface HistoricalSamplePort {
    /** The pages received for [dryRunId] when [wait] has passed or the sample is complete; may be incomplete. */
    fun pages(
        dryRunId: String,
        window: DryRunWindow,
        wait: Duration,
    ): List<SamplePage>

    /** Frees what the sample holds for [dryRunId]; the use case calls it exactly once at the end of every run. */
    fun release(dryRunId: String)
}

/** Asks for the sample of a dry run (owner ruling INV-Q-4870 B); the operation of INV-T-4974 calls it, not the run. */
interface SampleRequestPort {
    fun request(
        dryRunId: String,
        tenant: TenantId,
        window: DryRunWindow,
    )
}

/** The verdict or code of a sampled document under one pack version. */
interface PackEvaluationPort {
    fun evaluate(
        tenant: TenantId,
        packType: String,
        packVersion: String,
        document: SampledDocument,
    ): DocumentOutcome

    /** The `effectiveFrom` of the named pack version, the dividing line of straddling credit notes. */
    fun effectiveFrom(
        tenant: TenantId,
        packType: String,
        packVersion: String,
    ): LocalDate
}

/** What a run begins with: the ids, the pack versions and who started it, and when. */
data class DryRunStart(
    val dryRunId: String,
    val tenant: TenantId,
    val packType: String,
    val currentVersion: String,
    val candidateVersion: String,
    val startedBy: String,
    val startedAt: Instant,
)

enum class DryRunState { RUNNING, COMPLETE, SAMPLE_UNAVAILABLE }

data class StoredDryRun(
    val dryRunId: String,
    val startedBy: String,
    val state: DryRunState,
    val reason: String?,
    val delta: DryRunDelta?,
)

/** The only write path of a dry run: begin, then exactly one of complete or unavailable. */
interface DryRunDeltaStore {
    fun begin(start: DryRunStart)

    fun complete(
        dryRunId: String,
        completedAt: Instant,
        delta: DryRunDelta,
    )

    fun unavailable(
        dryRunId: String,
        reason: String,
    )

    /** The stored run, or null for an id that does not exist. */
    fun find(dryRunId: String): StoredDryRun?
}
