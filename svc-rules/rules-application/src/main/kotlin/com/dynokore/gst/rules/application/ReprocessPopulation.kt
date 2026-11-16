package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.reprocessing.PopulationItem
import com.dynokore.gst.rules.domain.reprocessing.ReprocessingSelection
import com.dynokore.platform.outboxandqueue.Lane

sealed interface ReprocessPopulationResult {
    data class Started(
        val run: ReprocessingRun,
    ) : ReprocessPopulationResult

    /** A repeat: the same run, no second request. */
    data class Resumed(
        val run: ReprocessingRun,
    ) : ReprocessPopulationResult

    data object Denied : ReprocessPopulationResult

    /** The precondition failed: no superseding pack is armed. Nothing is written. */
    data object NoSupersedingPackArmed : ReprocessPopulationResult
}

/** One page of the population of an entity. */
data class PopulationPage(
    val entityRef: String,
    val last: Boolean,
    val items: List<PopulationItem>,
)

/**
 * Reprocessing of the affected population on the REPROCESSING lane (INV-REQ-1003, 1004). It lists the documents to
 * resubmit and makes no resubmission call.
 */
@Suppress("LongParameterList") // constructor injection of the ports (CS-05)
class ReprocessPopulation(
    private val permission: PackCorrectionPermissionPort,
    private val runs: ReprocessingRunStore,
    private val requests: PopulationRequestPort,
    private val queue: ReprocessingQueuePort,
    private val resubmissions: ResubmissionStore,
    private val packs: SupersedingPackPort,
) {
    /** Starts the run for (pack type, version), or resumes it; the population is requested once per entity, once. */
    fun start(
        packType: String,
        version: String,
        entities: List<String>,
        actor: String,
        permissionKey: String,
    ): ReprocessPopulationResult {
        val existing = runs.find(packType, version)
        return when {
            !permission.mayDo(actor, permissionKey) -> {
                ReprocessPopulationResult.Denied
            }

            existing != null -> {
                ReprocessPopulationResult.Resumed(existing)
            }

            !packs.isArmed(packType, version) -> {
                ReprocessPopulationResult.NoSupersedingPackArmed
            }

            else -> {
                val run = runs.begin(packType, version, entities)
                entities.forEach { requests.request(packType, version, it) }
                ReprocessPopulationResult.Started(run)
            }
        }
    }

    /** A page arrived: each item goes to the queue on lane REPROCESSING, keyed by its Doc UUID. */
    fun pageArrived(
        packType: String,
        version: String,
        page: PopulationPage,
    ): ReprocessingRun {
        page.items.forEach {
            queue.enqueue(Lane.REPROCESSING, it.docUuid, ReprocessingWorkItem(packType, version, it))
        }
        runs.pageArrived(packType, version, page.entityRef, page.last, page.items.map { it.docUuid })
        return settle(packType, version)
    }

    /** Processes one item: an entry exactly as ReprocessingSelection decides, then the run progress. */
    fun process(
        packType: String,
        version: String,
        item: PopulationItem,
    ): ReprocessingRun {
        ReprocessingSelection.select(item, packs.verdict(packType, version, item))?.let {
            resubmissions.add(packType, version, it)
        }
        runs.processed(packType, version, item.docUuid)
        return settle(packType, version)
    }

    /** COMPLETE only when the last page has arrived and every item received is processed. */
    private fun settle(
        packType: String,
        version: String,
    ): ReprocessingRun {
        val run = checkNotNull(runs.find(packType, version)) { "no run for $packType $version" }
        return if (run.state == ReprocessingRunState.RUNNING && run.lastPageArrived && run.processed == run.received) {
            runs.complete(packType, version)
        } else {
            run
        }
    }
}
