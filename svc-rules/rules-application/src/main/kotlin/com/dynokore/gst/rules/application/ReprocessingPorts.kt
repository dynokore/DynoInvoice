package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.pack.EarlierPack
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SupersedingSpec
import com.dynokore.gst.rules.domain.reprocessing.PopulationItem
import com.dynokore.gst.rules.domain.reprocessing.ResubmissionEntry
import com.dynokore.platform.outboxandqueue.Lane
import java.time.LocalDate

/**
 * A scoped permission decision by AuthorityReplica.decide for the [key] the caller supplies; never a role name
 * (INV-Q-10027). Shared by rollback and reprocessing; the key of each operation is chosen by the wiring.
 */
fun interface PackCorrectionPermissionPort {
    fun mayDo(
        userId: String,
        key: String,
    ): Boolean
}

/** A registry row as the correction reads and writes it. */
data class CorrectionPackRow(
    val packType: String,
    val version: String,
    val effectiveFrom: LocalDate,
    val status: PackStatus,
    val contentRef: String,
)

/** The registry inside one transaction of the correction. */
interface PackCorrectionRegistryTx {
    fun find(
        packType: String,
        version: String,
    ): CorrectionPackRow?

    /** The latest earlier pack of the type that [bad] superseded, or null. */
    fun latestSuperseded(bad: CorrectionPackRow): EarlierPack?

    /** Writes exactly one new row and the copies of the artefacts of the pack version [copyFrom]. */
    fun insertSuperseding(
        spec: SupersedingSpec,
        copyFrom: String,
    ): CorrectionPackRow
}

/** Runs [work] in one transaction: all its writes commit together, or none does when it throws. */
interface PackCorrectionRegistry {
    fun <T> inTransaction(work: (PackCorrectionRegistryTx) -> T): T
}

enum class ReprocessingRunState { RUNNING, COMPLETE }

/** One reprocessing run, keyed by (pack type, version) of the superseding pack. */
data class ReprocessingRun(
    val packType: String,
    val version: String,
    val state: ReprocessingRunState,
    val lastPageArrived: Boolean,
    val received: Int,
    val processed: Int,
)

/** The run rows. The only writer of run state. */
interface ReprocessingRunStore {
    fun find(
        packType: String,
        version: String,
    ): ReprocessingRun?

    /** Writes the run row RUNNING for the requested [entities]. */
    fun begin(
        packType: String,
        version: String,
        entities: List<String>,
    ): ReprocessingRun

    /**
     * Records the arrival of a page of [entityRef] and the Doc UUIDs of its items (a Doc UUID counts once).
     * `lastPageArrived` of the run is true once the last page of every requested entity has arrived.
     */
    fun pageArrived(
        packType: String,
        version: String,
        entityRef: String,
        last: Boolean,
        docUuids: List<String>,
    ): ReprocessingRun

    /** Records one item as processed (a Doc UUID counts once). */
    fun processed(
        packType: String,
        version: String,
        docUuid: String,
    ): ReprocessingRun

    fun complete(
        packType: String,
        version: String,
    ): ReprocessingRun
}

/** Asks canonical-svc for the population of one entity (event-carried, no standing copy; INV-REQ-1196). */
fun interface PopulationRequestPort {
    fun request(
        packType: String,
        version: String,
        entityRef: String,
    )
}

/** What a queue item carries: the run key and the page item. */
data class ReprocessingWorkItem(
    val packType: String,
    val version: String,
    val item: PopulationItem,
)

/** The work queue. Reprocessing uses lane REPROCESSING only (INV-REQ-1004); the lane is a parameter on purpose. */
fun interface ReprocessingQueuePort {
    fun enqueue(
        lane: Lane,
        partitionKey: String,
        work: ReprocessingWorkItem,
    )
}

/**
 * The list of documents to resubmit. rules-svc only lists; it makes no resubmission call and has no port for one.
 * [add] is idempotent by the key (packType, version, entry.docUuid): the queue delivers at least once, so a
 * redelivered item must not add a second entry.
 */
interface ResubmissionStore {
    fun add(
        packType: String,
        version: String,
        entry: ResubmissionEntry,
    )
}

/** Whether the superseding pack is armed, and the verdict of a document under it. */
interface SupersedingPackPort {
    fun isArmed(
        packType: String,
        version: String,
    ): Boolean

    fun verdict(
        packType: String,
        version: String,
        item: PopulationItem,
    ): DocumentOutcome
}
