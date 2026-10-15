package com.dynokore.platform.partyregistry

import java.time.Instant

/** Who supplied an identifier value (INV-REQ-314). */
enum class ProvidedBy { INTERNAL, COUNTERPARTY, REGISTER, MIGRATED }

/** The supporting document that substantiates a value (INV-REQ-314: retained as record keeping). */
@JvmInline
value class EvidenceRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "an evidence reference must not be blank" }
    }
}

/**
 * Provenance of one identifier: `provided_by`, `evidence_ref` and `captured_at`, none of them optional (INV-REQ-314).
 * The constructor is private, so `MIGRATED` can only come from [migrated], the import path (INV-REQ-1111); a caller's
 * declaration goes through [declared], which refuses it.
 */
@ConsistentCopyVisibility
data class Provenance private constructor(
    val providedBy: ProvidedBy,
    val evidenceRef: EvidenceRef,
    val capturedAt: Instant,
) {
    companion object {
        /** A value declared by a caller: `INTERNAL`, `COUNTERPARTY` or `REGISTER`, never `MIGRATED`. */
        fun declared(
            providedBy: ProvidedBy,
            evidenceRef: EvidenceRef,
            capturedAt: Instant,
        ): Provenance {
            require(providedBy != ProvidedBy.MIGRATED) { "MIGRATED is set only by an import from a prior solution" }
            return Provenance(providedBy, evidenceRef, capturedAt)
        }

        /** A value imported from a prior solution (INV-REQ-1111); also how a stored `MIGRATED` row is read back. */
        fun migrated(
            evidenceRef: EvidenceRef,
            capturedAt: Instant,
        ): Provenance = Provenance(ProvidedBy.MIGRATED, evidenceRef, capturedAt)
    }
}
