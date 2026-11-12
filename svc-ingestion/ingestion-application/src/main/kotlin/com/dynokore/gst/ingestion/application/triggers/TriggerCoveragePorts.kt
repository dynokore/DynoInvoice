package com.dynokore.gst.ingestion.application.triggers

import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
import com.dynokore.gst.ingestion.domain.triggers.TriggerClass
import java.util.UUID

/** The operations the permission port is asked about; the key is decided per request, never a role. */
enum class CoverageOperation { MAP, WAIVE, COVER_ELSEWHERE }

/** The mode of a waiver command. */
enum class WaiverMode { REVIEW_WAIVER, COVERED_BY_OTHER_SOLUTION }

/** One stored change handed to the outbox: `TriggerCoverageChanged`. */
data class TriggerCoverageChanged(
    val tenantId: UUID,
    val entityRef: String,
    val triggerClass: TriggerClass,
    val record: CoverageRecord,
    val actorUserId: UUID,
)

/** Reads and writes rows in the caller's unit of work. */
interface TriggerCoverageStore {
    fun find(
        tenantId: UUID,
        entityRef: String,
        triggerClass: TriggerClass,
    ): CoverageRecord?

    fun save(
        tenantId: UUID,
        entityRef: String,
        triggerClass: TriggerClass,
        record: CoverageRecord,
        actorUserId: UUID,
    )
}

/** Hands one change to the outbox in the same unit of work as the store. */
fun interface TriggerCoverageEvents {
    fun publish(event: TriggerCoverageChanged)
}

/** Decides per request whether the acting user may perform [operation] on the entity. */
fun interface CoverageWritePermission {
    fun permits(
        actorUserId: UUID,
        operation: CoverageOperation,
        entityRef: String,
    ): Boolean
}
