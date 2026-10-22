package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.NaVerdict
import com.dynokore.platform.partyregistry.PartyField
import com.dynokore.platform.partyregistry.checkNaPopulation

/** The catalogue codes counterparty resolution reports (INV-REQ-315); the catalogue holds their severity. */
enum class FindingCode(
    val code: String,
) {
    MDM_001("MDM-001"),
    MDM_002("MDM-002"),
    MDM_004("MDM-004"),
    MDM_006("MDM-006"),
}

/** One finding of a resolution; [field] is a JSON pointer into the counterparty. */
data class Finding(
    val code: FindingCode,
    val field: String,
)

/** The value `NA` of Customer UEN, name, address line 1 and postal code for a scheme `NONE` (INV-REQ-313). */
const val NA = PartyField.NA_LITERAL

/**
 * INV-REQ-313, INV-REQ-315: what is wrong with a counterparty as held.
 *  - scheme `NONE` (an individual or a firm of individuals under their own names): Customer UEN, name, address line 1
 *    and postal code are all `NA`; anything else is a partial `NA` population, MDM-004;
 *  - any other scheme: no UEN (a UEN, else an overseas registration number) is MDM-002; `NA` anywhere is MDM-004,
 *    because `NA` is valid only as a whole and only for scheme `NONE`;
 *  - a legal name, address line 1 or postal code that is absent is MDM-006.
 * A UEN format failure (MDM-003) is a check of form that this function does not make.
 */
fun CounterpartyContent.findings(): List<Finding> {
    val none = identifiers.firstOrNull { it.scheme == IdentifierScheme.NONE }
    val uen =
        none?.value
            ?: identifiers.firstOrNull { it.scheme == IdentifierScheme.UEN }?.value
            ?: identifiers.firstOrNull { it.scheme == IdentifierScheme.OVERSEAS_REGISTRATION }?.value
    val name = legalName.presentOrNull()
    val line1 = address?.line1.presentOrNull()
    val postal = address?.postalCode.presentOrNull()
    val found = mutableListOf<Finding>()
    if (none == null && uen == null) found += Finding(FindingCode.MDM_002, "/resolvedIdentifiers")
    if (naPopulationBroken(none != null, uen, name, line1, postal)) found += Finding(FindingCode.MDM_004, "/")
    val missing =
        listOfNotNull(
            "/legalName".takeIf { name == null },
            "/address/addressLine1".takeIf { line1 == null },
            "/address/postalCode".takeIf { postal == null },
        )
    missing.forEach { found += Finding(FindingCode.MDM_006, it) }
    return found
}

private fun String?.presentOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun naPopulationBroken(
    none: Boolean,
    uen: String?,
    name: String?,
    line1: String?,
    postal: String?,
): Boolean {
    val present = listOfNotNull(uen, name, line1, postal).map(PartyField::of)
    if (present.size == FOUR) {
        return when (val verdict = checkNaPopulation(present[0], present[1], present[2], present[3])) {
            is NaVerdict.PartialNa -> true
            is NaVerdict.Accepted -> verdict.allNa != none
        }
    }
    val na = present.count { it == PartyField.NotApplicable }
    return if (none) na != present.size else na > 0
}

private const val FOUR = 4
