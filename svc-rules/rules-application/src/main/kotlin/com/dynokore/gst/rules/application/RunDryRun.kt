package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.ItemReading
import com.dynokore.gst.rules.domain.dryrun.SampleItems
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
 * not attributable, a changed document without a supply type or a fault of the sample or evaluation port ends
 * SAMPLE_UNAVAILABLE and stores no delta.
 * The sample is released
 * exactly once at the end of every run.
 */
class RunDryRun(
    private val samples: HistoricalSamplePort,
    private val evaluation: PackEvaluationPort,
    private val store: DryRunDeltaStore,
    private val clock: Clock,
) {
    /**
     * Every call of the store is made outside the catch of [compute]: a fault of the store reaches the caller unchanged
     * (OWASP ASVS V7, Twelve-Factor backing services). The sample is released once, whatever the end, `begin` included.
     */
    fun run(command: RunDryRunCommand): DryRunOutcome =
        try {
            store.begin(command.start)
            when (val computed = compute(command)) {
                is Computed.Done -> {
                    store.complete(command.dryRunId, clock.instant(), computed.delta)
                    DryRunOutcome.Complete(computed.delta.changedCount)
                }

                is Computed.Failed -> {
                    store.unavailable(command.dryRunId, computed.reason)
                    DryRunOutcome.Unavailable(computed.reason)
                }
            }
        } finally {
            samples.release(command.dryRunId)
        }

    private sealed interface Computed {
        data class Done(
            val delta: DryRunDelta,
        ) : Computed

        data class Failed(
            val reason: String,
        ) : Computed
    }

    private class SampleUnavailable(
        reason: String,
    ) : RuntimeException(reason)

    /** Fail closed for the named set only: a refused sample, or a fault of the sample or evaluation port. */
    private fun compute(command: RunDryRunCommand): Computed =
        try {
            Computed.Done(execute(command))
        } catch (e: SampleUnavailable) {
            Computed.Failed(e.message.orEmpty())
        } catch (e: IllegalStateException) {
            Computed.Failed("evaluation failed: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            Computed.Failed("evaluation failed: ${e.javaClass.simpleName}")
        }

    private fun execute(command: RunDryRunCommand): DryRunDelta {
        val documents = readSample(command)
        val outcomes =
            documents.map { d ->
                val old = outcomeOf(command, command.currentVersion, d)
                Triple(d, old, outcomeOf(command, command.candidateVersion, d))
            }
        outcomes
            .firstOrNull { (d, old, new) -> d.supplyType == null && DryRunComputation.changeTypeOf(old, new) != null }
            ?.let { (d) ->
                throw SampleUnavailable(
                    "sample item without supplyType for a changed document: dry run ${command.dryRunId}, " +
                        "page ${d.pageNumber}, entityRef ${d.entityRef}",
                )
            }
        val effectiveFrom = evaluation.effectiveFrom(command.tenant, command.packType, command.candidateVersion)
        return DryRunComputation.delta(outcomes, effectiveFrom)
    }

    private fun readSample(command: RunDryRunCommand): List<SampledDocument> {
        val pages = samples.pages(command.dryRunId, command.window, command.sampleWait).sortedBy { it.number }
        missingPage(pages)?.let { throw SampleUnavailable("sample incomplete: page $it is missing") }
        return pages.flatMap { page ->
            page.documents.mapIndexed { index, raw ->
                when (val read = SampleItems.read(page.number, index + 1, raw)) {
                    is ItemReading.Read -> read.document
                    is ItemReading.Refused -> throw SampleUnavailable("sample item unusable, ${read.refusal}")
                }
            }
        }
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
}
