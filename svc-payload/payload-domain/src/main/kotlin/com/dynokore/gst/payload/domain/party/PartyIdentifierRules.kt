package com.dynokore.gst.payload.domain.party

/** The scheme value that marks a Singapore UEN (INV-Q-7104 A, INV-REQ-485 behavior 3). */
const val SG_UEN_SCHEME = "SG_UEN"

/** The identity triple canonical-svc stored with the version (INV-Q-7142 A, INV-REQ-699 behavior 1). */
data class IdentityTriple(
    val senderId: String,
    val supplierId: String,
    val supplierGstn: String,
)

/** A party identifier as the canonical document carries it: scheme and value. */
data class PartyInput(
    val identifierScheme: String,
    val identifierValue: String,
)

/** What the party identifier rules read. `callerSupplier` is carried but never read (INV-REQ-699 behavior 1). */
data class PartyIdentifierInput(
    val buyer: PartyInput,
    val callerSupplier: PartyInput,
    val identityTriple: IdentityTriple?,
)

/** Why no party identifiers are resolved. */
enum class RefusalReason {
    IDENTITY_TRIPLE_ABSENT,
    DOCUMENT_NUMBER_ABSENT,
    DOCUMENT_DATE_ABSENT,
}

/** The result of the party identifier rules; no member carries a document. */
sealed interface PartyIdentifiers {
    data class Resolved(
        val buyerLegalRegistrationId: String?,
        val senderId: String,
        val supplierId: String,
        val supplierGstn: String,
    ) : PartyIdentifiers

    data class Refused(
        val reason: RefusalReason,
    ) : PartyIdentifiers
}

/** INV-REQ-699 behavior 1 and INV-REQ-485 behavior 3 as a pure function (INV-Q-7142 A, INV-Q-7104 A, INV-Q-7173 A). */
object PartyIdentifierRules {
    fun resolve(input: PartyIdentifierInput): PartyIdentifiers {
        val triple = input.identityTriple ?: return PartyIdentifiers.Refused(RefusalReason.IDENTITY_TRIPLE_ABSENT)
        val buyerUen =
            if (input.buyer.identifierScheme == SG_UEN_SCHEME) input.buyer.identifierValue else null
        return PartyIdentifiers.Resolved(
            buyerLegalRegistrationId = buyerUen,
            senderId = triple.senderId,
            supplierId = triple.supplierId,
            supplierGstn = triple.supplierGstn,
        )
    }
}
