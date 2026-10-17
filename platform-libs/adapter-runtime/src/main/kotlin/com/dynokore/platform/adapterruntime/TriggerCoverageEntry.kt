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

    /** Covered by another solution: needs evidence and an `entity.admin` acknowledgement (INV-REQ-1322). */
    data class CoveredElsewhere(
        val evidenceRef: String,
        val acknowledgedBy: String,
        val acknowledgerRoles: Set<String>,
    ) : TriggerCoverageEntry {
        init {
            require(evidenceRef.isNotBlank()) { "evidenceRef must not be blank" }
            require(acknowledgedBy.isNotBlank()) { "acknowledgedBy must not be blank" }
            require(TriggerCoverage.ENTITY_ADMIN in acknowledgerRoles) {
                "covering a class by another solution needs ${TriggerCoverage.ENTITY_ADMIN} acknowledgement"
            }
        }
    }
}
