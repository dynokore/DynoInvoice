package com.dynokore.platform.partyregistry

/** One finding of [PartyChecks]. [errorCode] is the Keel error code where Keel assigns one. */
data class PartyFinding(
    val errorCode: String?,
    val field: String,
    val detail: String,
)

/** A counterparty as captured: the four NA-governed fields and its identifiers. */
data class CounterpartyDraft(
    val uen: PartyField,
    val name: PartyField,
    val addressLine1: PartyField,
    val postalCode: PartyField,
    val identifiers: List<Identifier>,
)

/**
 * Composes the NA rule and the injected scheme hook over a counterparty (INV-REQ-313). A UEN that fails the hook is
 * MDM-003; Keel assigns no code to a GSTN or Peppol format failure, so those carry `errorCode = null`.
 */
class PartyChecks(
    private val schemes: SchemeValidator,
) {
    fun check(draft: CounterpartyDraft): List<PartyFinding> {
        val findings = mutableListOf<PartyFinding>()
        val na = checkNaPopulation(draft.uen, draft.name, draft.addressLine1, draft.postalCode)
        if (na is NaVerdict.PartialNa) {
            findings += PartyFinding(na.errorCode, na.naFields.sorted().joinToString(","), "partial NA population")
        }
        draft.identifiers.filter { it.scheme != IdentifierScheme.NONE }.forEach { id ->
            val verdict = schemes.validate(id.scheme, id.value)
            if (verdict is FormatVerdict.Invalid) {
                val code = if (id.scheme == IdentifierScheme.UEN) "MDM-003" else null
                findings += PartyFinding(code, id.scheme.name, verdict.reason)
            }
        }
        return findings
    }
}
