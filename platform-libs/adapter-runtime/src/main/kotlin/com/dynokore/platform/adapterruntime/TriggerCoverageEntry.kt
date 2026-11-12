package com.dynokore.platform.adapterruntime

import java.time.LocalDate

/** How one [TriggerClass] of an entity is covered (INV-REQ-788, INV-REQ-1322). */
sealed interface TriggerCoverageEntry {
    /** Mapped to an adapter. */
    data class Mapped(
        val adapterId: String,
    ) : TriggerCoverageEntry {
        init {
            require(adapterId.isNotBlank()) { "adapterId must not be blank" }
        }
    }

    /** Waived with a reason and a review date (INV-REQ-953). The review date is data; nothing here reads a clock. */
    data class Waived(
        val reason: String,
        val reviewDate: LocalDate,
    ) : TriggerCoverageEntry {
        init {
            require(reason.isNotBlank()) { "a waiver must carry a reason" }
        }
    }

    /**
     * Covered by another solution: needs an evidence reference and the acting user id (INV-REQ-1322).
     * Whether the acknowledging user may record this is decided by the calling service, per request, from the
     * scoped permission key the generated operation-to-roles map names for the operation, through
     * `AuthorityReplica.decide` (INV-Q-10027). [acknowledgedBy] is the acting user id, never a role (INV-Q-10647).
     */
    data class CoveredElsewhere(
        val evidenceRef: String,
        val acknowledgedBy: String,
    ) : TriggerCoverageEntry {
        init {
            require(evidenceRef.isNotBlank()) { "evidenceRef must not be blank" }
            require(acknowledgedBy.isNotBlank()) { "acknowledgedBy must not be blank" }
        }
    }
}
