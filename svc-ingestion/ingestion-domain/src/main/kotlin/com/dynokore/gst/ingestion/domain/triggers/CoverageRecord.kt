package com.dynokore.gst.ingestion.domain.triggers

import java.time.LocalDate

/** How one trigger class of an entity is covered (INV-REQ-788); members are validated on construction. */
sealed interface CoverageRecord {
    /** Mapped to an adapter. */
    data class Mapped(
        val adapterRef: String,
    ) : CoverageRecord {
        init {
            require(adapterRef.isNotBlank() && adapterRef.length <= MAX_ADAPTER_REF) {
                "adapterRef must be 1 to $MAX_ADAPTER_REF characters and not blank"
            }
        }
    }

    /** Waived with a review date (INV-REQ-953). */
    data class Waived(
        val reviewDate: LocalDate,
    ) : CoverageRecord

    /** Covered by another solution, with evidence (INV-REQ-1322). */
    data class CoveredElsewhere(
        val coveringSolution: String,
        val evidenceReference: String,
    ) : CoverageRecord {
        init {
            require(coveringSolution.isNotBlank() && coveringSolution.length <= MAX_TEXT) {
                "coveringSolution must be 1 to $MAX_TEXT characters and not blank"
            }
            require(evidenceReference.isNotBlank() && evidenceReference.length <= MAX_TEXT) {
                "evidenceReference must be 1 to $MAX_TEXT characters and not blank"
            }
        }
    }

    companion object {
        const val MAX_ADAPTER_REF = 40
        const val MAX_TEXT = 200
    }
}
