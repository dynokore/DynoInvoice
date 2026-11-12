package com.dynokore.gst.ingestion.application.triggers

import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
import com.dynokore.gst.ingestion.domain.triggers.TriggerClass
import com.dynokore.gst.ingestion.domain.triggers.TriggerCoverageDecision
import java.time.LocalDate
import java.util.UUID

/** Result of a coverage write. */
sealed interface CoverageOutcome {
    /** First write: stored and published once. */
    data class Stored(
        val record: CoverageRecord,
    ) : CoverageOutcome

    /** Same content again: the stored record, nothing stored or published. */
    data class Unchanged(
        val record: CoverageRecord,
    ) : CoverageOutcome

    /** Different content for a class already recorded: nothing changed. */
    data class PreconditionFailed(
        val existing: CoverageRecord,
    ) : CoverageOutcome

    /** The permission port refused: nothing stored or published. */
    data object Forbidden : CoverageOutcome
}

data class MapTriggerClassCommand(
    val tenantId: UUID,
    val entityRef: String,
    val actorUserId: UUID,
    val triggerClass: TriggerClass,
    val adapterRef: String,
)

data class WaiveTriggerClassCommand(
    val tenantId: UUID,
    val entityRef: String,
    val actorUserId: UUID,
    val triggerClass: TriggerClass,
    val mode: WaiverMode,
    val reviewDate: LocalDate? = null,
    val coveringSolution: String? = null,
    val evidenceReference: String? = null,
)

private data class Target(
    val tenantId: UUID,
    val entityRef: String,
    val actorUserId: UUID,
    val triggerClass: TriggerClass,
)

private class CoverageWriter(
    private val store: TriggerCoverageStore,
    private val events: TriggerCoverageEvents,
    private val permission: CoverageWritePermission,
) {
    fun write(
        operation: CoverageOperation,
        target: Target,
        build: () -> CoverageRecord,
    ): CoverageOutcome {
        if (!permission.permits(target.actorUserId, operation, target.entityRef)) return CoverageOutcome.Forbidden
        val record = build()
        val existing = store.find(target.tenantId, target.entityRef, target.triggerClass)
        return if (existing == null) storeAndPublish(target, record) else outcomeForExisting(existing, record)
    }

    private fun storeAndPublish(
        target: Target,
        record: CoverageRecord,
    ): CoverageOutcome {
        store.save(target.tenantId, target.entityRef, target.triggerClass, record, target.actorUserId)
        events.publish(
            TriggerCoverageChanged(target.tenantId, target.entityRef, target.triggerClass, record, target.actorUserId),
        )
        return CoverageOutcome.Stored(record)
    }

    private fun outcomeForExisting(
        existing: CoverageRecord,
        record: CoverageRecord,
    ): CoverageOutcome =
        if (existing == record) CoverageOutcome.Unchanged(existing) else CoverageOutcome.PreconditionFailed(existing)
}

/** Records a class as mapped to an adapter (INV-REQ-788). Port failures propagate so the unit of work rolls back. */
class MapTriggerClass(
    store: TriggerCoverageStore,
    events: TriggerCoverageEvents,
    permission: CoverageWritePermission,
) {
    private val writer = CoverageWriter(store, events, permission)

    fun execute(command: MapTriggerClassCommand): CoverageOutcome =
        writer.write(CoverageOperation.MAP, command.target()) { TriggerCoverageDecision.mapped(command.adapterRef) }
}

/** Records a class as waived or as covered by another solution (INV-REQ-788, INV-REQ-1322). */
class WaiveTriggerClass(
    store: TriggerCoverageStore,
    events: TriggerCoverageEvents,
    permission: CoverageWritePermission,
) {
    private val writer = CoverageWriter(store, events, permission)

    fun execute(command: WaiveTriggerClassCommand): CoverageOutcome {
        val operation =
            when (command.mode) {
                WaiverMode.REVIEW_WAIVER -> CoverageOperation.WAIVE
                WaiverMode.COVERED_BY_OTHER_SOLUTION -> CoverageOperation.COVER_ELSEWHERE
            }
        return writer.write(operation, command.target()) {
            when (command.mode) {
                WaiverMode.REVIEW_WAIVER -> {
                    TriggerCoverageDecision.waived(command.reviewDate)
                }

                WaiverMode.COVERED_BY_OTHER_SOLUTION -> {
                    TriggerCoverageDecision.coveredElsewhere(
                        command.coveringSolution.orEmpty(),
                        command.evidenceReference.orEmpty(),
                    )
                }
            }
        }
    }
}

private fun MapTriggerClassCommand.target() = Target(tenantId, entityRef, actorUserId, triggerClass)

private fun WaiveTriggerClassCommand.target() = Target(tenantId, entityRef, actorUserId, triggerClass)
