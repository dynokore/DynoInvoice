package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import java.time.Instant
import java.time.LocalDate

/**
 * Where a write comes from, which fixes the provenance it records (INV-REQ-314, INV-REQ-1111). A value entered by the
 * business is `INTERNAL`; one the counterparty confirmed through its signed link is `COUNTERPARTY`; `MIGRATED` is set
 * only by [IMPORT], the applied import from a prior solution. No caller supplies a `providedBy`.
 */
enum class WriteOrigin { OPERATOR, COUNTERPARTY_LINK, IMPORT }

/** An identifier a write supplies; the provenance is added by [CounterpartyContent.after]. */
data class IdentifierInput(
    val scheme: IdentifierScheme,
    val value: String,
)

/** An approved scheme status item a write supplies; an absent evidence reference falls back to the write's own. */
data class SchemeInput(
    val scheme: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val evidenceRef: String?,
)

/**
 * One write to a counterparty. A part that is absent keeps what the previous version held; the identifiers are
 * merged by scheme (a supplied scheme replaces the held one, no other is dropped). Updates are last-write-wins
 * (INV-REQ-2576): nothing here looks at whether another update intervened.
 */
data class CounterpartyChange(
    val identifiers: List<IdentifierInput>,
    val legalName: String?,
    val address: PostalAddress?,
    val arcsGstChargeAgreed: Boolean?,
    val approvedSchemes: List<SchemeInput>?,
)

/** The content the version after [previous] holds once [change] is written with [evidenceRef] at [at]. */
fun CounterpartyContent?.after(
    change: CounterpartyChange,
    evidenceRef: String,
    origin: WriteOrigin,
    at: Instant,
): CounterpartyContent {
    val evidence = EvidenceRef(evidenceRef)
    val provenance = provenanceOf(origin, evidence, at)
    val held = this?.identifiers.orEmpty().associateBy { it.scheme }
    val written = change.identifiers.map { Identifier(it.scheme, it.value, provenance) }
    val merged = (held + written.associateBy { it.scheme }).values.toList()
    val newest = (written + held.values).maxByOrNull { it.provenance.capturedAt }
    return CounterpartyContent(
        identifiers = merged,
        legalName = change.legalName?.trim()?.takeIf { it.isNotEmpty() } ?: this?.legalName,
        address = change.address ?: this?.address,
        arcs =
            change.arcsGstChargeAgreed?.let { ArcsDefault(it, provenance) } ?: this?.arcs,
        schemes =
            change.approvedSchemes?.map {
                SchemeStatus(
                    it.scheme,
                    it.effectiveFrom,
                    it.effectiveTo,
                    provenanceOf(origin, it.evidenceRef?.let(::EvidenceRef) ?: evidence, at),
                )
            } ?: this?.schemes.orEmpty(),
        providedBy = newest?.provenance?.providedBy ?: provenance.providedBy,
    )
}

private fun provenanceOf(
    origin: WriteOrigin,
    evidence: EvidenceRef,
    at: Instant,
): Provenance =
    when (origin) {
        WriteOrigin.OPERATOR -> Provenance.declared(ProvidedBy.INTERNAL, evidence, at)
        WriteOrigin.COUNTERPARTY_LINK -> Provenance.declared(ProvidedBy.COUNTERPARTY, evidence, at)
        WriteOrigin.IMPORT -> Provenance.migrated(evidence, at)
    }
