package com.dynokore.gst.controlPlane.adapters.stage

import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
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
    fun record(outcome: StagingOutcome): UUID {
        // The Name form of DSL.function: no generated routine class is used (INV-Q-7457).
        val call =
            DSL.function(
                DSL.name("controlplane", "record_staging_result"),
                SQLDataType.UUID,
                text(outcome.packRef.value),
                text(outcome.result.name),
                text(outcome.packContentHash.hex),
                text(outcome.target),
                text(outcome.evidenceRef),
                text(outcome.replayedPeriodRef),
                text(outcome.recordedBy),
            )
        return checkNotNull(dsl.select(call).fetchSingle().value1())
    }

    private fun text(value: String?) = DSL.value(value, SQLDataType.CLOB)
}
