package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation
import com.dynokore.gst.rules.domain.dryrun.ItemReading
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

data class RunDryRunCommand(
    val start: DryRunStart,
    val window: DryRunWindow,
    val sampleWait: Duration,
) {
    val dryRunId: String get() = start.dryRunId
    val tenant: TenantId get() = start.tenant
    val packType: String get() = start.packType
    val currentVersion: String get() = start.currentVersion
    val candidateVersion: String get() = start.candidateVersion
}

sealed interface DryRunOutcome {
    data class Complete(
        val changedCount: Int,
    ) : DryRunOutcome

    data class Unavailable(
        val reason: String,
    ) : DryRunOutcome
}

/**
 * Runs one dry run (INV-REQ-772, INV-REQ-1352, INV-REQ-1303): reads the sample, evaluates every document under the
 * current and the candidate pack, and hands the delta to the store. Fail closed: an incomplete sample, an item that is
 * not attributable or an evaluation that throws ends SAMPLE_UNAVAILABLE and stores no delta. The sample is released
 * exactly once at the end of every run.
 */
class RunDryRun(
    private val samples: HistoricalSamplePort,
    private val evaluation: PackEvaluationPort,
    private val store: DryRunDeltaStore,
    private val clock: Clock,
) {
    fun run(command: RunDryRunCommand): DryRunOutcome {
        store.begin(command.start)
        try {
            return execute(command)
        } catch (e: Exception) {
            return unavailable(command, "evaluation failed: ${e.javaClass.simpleName}")
        } finally {
            samples.release(command.dryRunId)
        }
    }

    private fun execute(command: RunDryRunCommand): DryRunOutcome {
        val pages = samples.pages(command.dryRunId, command.window, command.sampleWait).sortedBy { it.number }
        missingPage(pages)?.let { return unavailable(command, "sample incomplete: page $it is missing") }
        val documents = mutableListOf<SampledDocument>()
        for (page in pages) {
            page.documents.forEachIndexed { index, raw ->
                when (val read = DryRunComputation.read(page.number, index + 1, raw)) {
                    is ItemReading.Read -> documents += read.document
                    is ItemReading.Refused -> return unavailable(command, "sample item unusable, ${read.refusal}")
                }
            }
        }
        val outcomes =
            documents.map { d ->
                Triple(
                    d,
                    outcomeOf(command, command.currentVersion, d),
                    outcomeOf(command, command.candidateVersion, d),
                )
            }
        val effectiveFrom = evaluation.effectiveFrom(command.tenant, command.packType, command.candidateVersion)
        val delta = DryRunComputation.delta(outcomes, effectiveFrom)
        store.complete(command.dryRunId, clock.instant(), delta)
        return DryRunOutcome.Complete(delta.changedCount)
    }

    private fun outcomeOf(
        command: RunDryRunCommand,
        version: String,
        document: SampledDocument,
    ): DocumentOutcome = evaluation.evaluate(command.tenant, command.packType, version, document)

    /** The first page number missing: pages must run from 1 to a page flagged last. */
    private fun missingPage(pages: List<SamplePage>): Int? {
        val numbers = pages.map { it.number }.toSet()
        val last = pages.filter { it.lastPage }.maxOfOrNull { it.number }
        val upTo = last ?: ((numbers.maxOrNull() ?: 0) + 1)
        return (1..upTo).firstOrNull { it !in numbers }
    }

    private fun unavailable(
        command: RunDryRunCommand,
        reason: String,
    ): DryRunOutcome {
        store.unavailable(command.dryRunId, reason)
        return DryRunOutcome.Unavailable(reason)
    }
}
