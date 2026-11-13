package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.arrival.FiledPeriods
import com.dynokore.gst.submission.application.filing.FilingFactStore

/**
 * [FiledPeriods] over the [FilingFactStore] port (INV-T-21094): a period is filed exactly when the store holds at
 * least one fact of the entity and period for the bound tenant. It opens no transaction (ARC-21) and writes nothing.
 */
class FilingFactFiledPeriods(
    private val facts: FilingFactStore,
) : FiledPeriods {
    override fun isFiled(
        entityRef: String,
        periodRef: String,
    ): Boolean = facts.facts(entityRef, periodRef).isNotEmpty()
}
