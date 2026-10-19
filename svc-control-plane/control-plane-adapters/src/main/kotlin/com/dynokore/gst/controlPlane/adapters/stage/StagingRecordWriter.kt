package com.dynokore.gst.controlPlane.adapters.stage

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.routines.references.recordStagingResult
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import org.jooq.DSLContext
import java.util.UUID

/** What the stage records for a pack version (the `PackStagingRecord` of the contract). */
data class StagingOutcome(
    val packRef: PackRef,
    val result: StagingResult,
    val packContentHash: ContentHash,
    val target: String?,
    val evidenceRef: String?,
    val replayedPeriodRef: String?,
    val recordedBy: String,
)

/**
 * The only writer of the staging evidence table (coordinator ruling INV-Q-4878): it calls the sys-owned database
 * function `record_staging_result`, the one write path, because the runtime login holds no INSERT, UPDATE or DELETE on
 * the table. Only the stage of INV-T-4985 (this package) may use it: a Konsist rule fails `check` for any other
 * package that names this class. No HTTP operation writes it.
 */
class StagingRecordWriter(
    private val dsl: DSLContext,
) {
    fun record(outcome: StagingOutcome): UUID =
        checkNotNull(
            recordStagingResult(
                dsl.configuration(),
                outcome.packRef.value,
                outcome.result.name,
                outcome.packContentHash.hex,
                outcome.target,
                outcome.evidenceRef,
                outcome.replayedPeriodRef,
                outcome.recordedBy,
            ),
        )
}
