package com.dynokore.gst.rules.domain.reprocessing

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation

/** One document of a population page: its Doc UUID, the pack version it was classified under and its stored verdict. */
data class PopulationItem(
    val docUuid: String,
    val supersededVersion: String,
    val acknowledged: Boolean,
    val storedVerdict: DocumentOutcome,
)

/** The resubmission of one document under the same Doc UUID (INV-REQ-1003); both members are the item's, unchanged. */
data class ResubmissionEntry(
    val docUuid: String,
    val supersededVersion: String,
)

/** Which items are listed for resubmission. Pure. */
object ReprocessingSelection {
    /** Whether the new verdict differs from the stored one, by the dry-run comparison. */
    fun changed(
        stored: DocumentOutcome,
        new: DocumentOutcome,
    ): Boolean = DryRunComputation.changeTypeOf(stored, new) != null

    /** An entry exactly when the item is acknowledged and its verdict changed. */
    fun select(
        item: PopulationItem,
        newVerdict: DocumentOutcome,
    ): ResubmissionEntry? =
        if (item.acknowledged && changed(item.storedVerdict, newVerdict)) {
            ResubmissionEntry(item.docUuid, item.supersededVersion)
        } else {
            null
        }
}
