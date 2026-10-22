package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import java.time.Instant
import java.time.LocalDate

/** The reference of a counterparty within a tenant (`counterpartyRef` of the contract). */
@JvmInline
value class PartyRef(
    val value: String,
) {
    init {
        require(value.length in 1..MAX) { "a party reference is 1 to $MAX characters" }
    }

    private companion object {
        const val MAX = 40
    }
}

/** The postal address as held; every part may be absent, which is a finding of the resolution, not a refusal. */
data class PostalAddress(
    val line1: String?,
    val line2: String?,
    val postalCode: String?,
)

/** The ARCS written-agreement default with the provenance of the write that set it (INV-REQ-2434, INV-REQ-2446). */
data class ArcsDefault(
    val agreed: Boolean,
    val provenance: Provenance,
)

/** One approved scheme status item with its provenance and evidence reference (INV-REQ-2433, INV-REQ-2445). */
data class SchemeStatus(
    val scheme: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val provenance: Provenance,
) {
    init {
        require(scheme.length in 1..MAX_SCHEME) { "a scheme is 1 to $MAX_SCHEME characters" }
        require(effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) { "effectiveTo is before effectiveFrom" }
    }

    /** In force on [date]: from `effectiveFrom` to `effectiveTo`, both days included. */
    fun inForceOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))

    private companion object {
        const val MAX_SCHEME = 20
    }
}

/** What one version of a counterparty holds. */
data class CounterpartyContent(
    val identifiers: List<Identifier>,
    val legalName: String?,
    val address: PostalAddress?,
    val arcs: ArcsDefault?,
    val schemes: List<SchemeStatus>,
    val providedBy: ProvidedBy,
)

/** One version of a counterparty: versions are numbered from 1 in write order and the highest is current. */
data class CounterpartyVersion(
    val partyRef: PartyRef,
    val version: Long,
    val recordedAt: Instant,
    val content: CounterpartyContent,
)
