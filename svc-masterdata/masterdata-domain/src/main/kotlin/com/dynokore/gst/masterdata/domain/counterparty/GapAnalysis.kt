package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.IdentifierScheme

/** The five gap classes of the pre-go-live gap report (INV-REQ-316), by the `findingClass` wire value. */
enum class GapClass(
    val errorCode: String?,
) {
    UEN_ABSENT("MDM-002"),
    UEN_INVALID("MDM-003"),
    GST_REGISTER_CONFLICT("MDM-007"),
    ADDRESS_INCOMPLETE("MDM-006"),

    /** Carries no error code (the contract's `GapReport.rows.errorCode` is absent for it). */
    DUPLICATE_UEN(null),
}

/** One counterparty listed under one class. */
data class GapRow(
    val party: PartyRef,
    val gapClass: GapClass,
)

/**
 * The UEN a counterparty is identified by for the gap report: the value of its `UEN` identifier. `NA` (scheme `NONE`)
 * and an overseas registration are not UENs, so they are neither absent, invalid nor duplicate here.
 */
fun CounterpartyContent.uenOrNull(): String? =
    identifiers
        .firstOrNull { it.scheme == IdentifierScheme.UEN }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** The GSTN the counterparty holds, trimmed; null when none. */
fun CounterpartyContent.gstnOrNull(): String? =
    identifiers
        .firstOrNull { it.scheme == IdentifierScheme.GSTN }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * INV-REQ-316 classes 1, 2, 4 and 5, from the current versions only; class 3 needs the register and is made by the
 * caller. A pure function: it reads nothing and writes nothing (INV-AC-1609). [uenFormatValid] is the UEN checksum and
 * format check; this module fixes none itself.
 *
 *  1. UEN_ABSENT: the counterparty holds no UEN and no `NA` classification (the MDM-002 finding of resolution);
 *  2. UEN_INVALID: it holds a UEN that [uenFormatValid] refuses;
 *  4. ADDRESS_INCOMPLETE: address line 1 or the postal code is missing;
 *  5. DUPLICATE_UEN: another counterparty holds the same UEN (compared trimmed and upper-cased); every member is listed.
 * Rows are ordered by party reference, then class, so two runs over equal data answer equal rows.
 */
fun gapRows(
    current: List<CounterpartyVersion>,
    uenFormatValid: (String) -> Boolean,
): List<GapRow> {
    val rows = mutableListOf<GapRow>()
    current.forEach { version ->
        val content = version.content
        val party = version.partyRef
        if (content.findings().any { it.code == FindingCode.MDM_002 }) rows += GapRow(party, GapClass.UEN_ABSENT)
        content.uenOrNull()?.let { uen -> if (!uenFormatValid(uen)) rows += GapRow(party, GapClass.UEN_INVALID) }
        val address = content.address
        if (address?.line1.isBlankOrNull() || address?.postalCode.isBlankOrNull()) {
            rows += GapRow(party, GapClass.ADDRESS_INCOMPLETE)
        }
    }
    current
        .mapNotNull { v -> v.content.uenOrNull()?.let { it.uppercase() to v.partyRef } }
        .groupBy({ it.first }, { it.second })
        .values
        .filter { it.size > 1 }
        .flatten()
        .forEach { rows += GapRow(it, GapClass.DUPLICATE_UEN) }
    return rows.sortedWith(compareBy({ it.party.value }, { it.gapClass.ordinal }))
}

/**
 * Class 3 (MDM-007): the counterparty records a GSTN, and the register, looked up for its UEN, either holds another GST
 * number or does not show the UEN as REGISTERED. A counterparty without a GSTN records no GST registration, so there is
 * nothing to differ; a UEN the register does not know says nothing (found = false) and is not a conflict.
 */
fun conflictsWithRegister(
    heldGstn: String?,
    registerFound: Boolean,
    registerStatus: String,
    registerGstn: String?,
): Boolean {
    if (heldGstn == null || !registerFound) return false
    val other = registerGstn?.trim()?.takeIf { it.isNotEmpty() }
    return (other != null && other != heldGstn) || registerStatus != "REGISTERED"
}

private fun String?.isBlankOrNull(): Boolean = this == null || this.isBlank()
