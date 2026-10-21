package com.dynokore.gst.masterdata.domain.identity

import java.time.LocalDate

/**
 * The supplier identity triple of INV-REQ-310: Sender ID (the Peppol ID of the envelope), Supplier ID (IBT-030) and
 * Supplier GSTN (IBT-031). The three are configured values; none is derived from another, from the entity's own GSTN or
 * from who submits (INV-REQ-311, INV-REQ-803).
 */
data class IdentityTriple(
    val senderId: String,
    val supplierId: String,
    val supplierGstn: String,
) {
    init {
        require(senderId.isNotBlank() && senderId.length <= SENDER_MAX) { "Sender ID is 1 to $SENDER_MAX characters" }
        require(supplierId.isNotBlank() && supplierId.length <= SUPPLIER_MAX) {
            "Supplier ID is 1 to $SUPPLIER_MAX characters"
        }
        require(supplierGstn.isNotBlank() && supplierGstn.length <= GSTN_MAX) {
            "Supplier GSTN is 1 to $GSTN_MAX characters"
        }
    }

    companion object {
        const val SENDER_MAX = 64
        const val SUPPLIER_MAX = 64
        const val GSTN_MAX = 20
    }
}

/** One effective-dated triple of an entity under a constitution; it applies to documents dated from [effectiveFrom]. */
data class TripleVersion(
    val entity: EntityRef,
    val constitution: Constitution,
    val triple: IdentityTriple,
    val effectiveFrom: LocalDate,
)

/** The state of a proposed change (contract: `PendingIdentityTripleChange.state`). */
enum class ChangeState {
    /** Proposed; nothing is in force until a different person confirms it. */
    PENDING_CONFIRMATION,

    /** Confirmed by a different person; the version applies from its date. */
    CONFIRMED,

    /** Replaced by a later proposal for the same entity, constitution and effective date (the contract's repeatKey). */
    SUPERSEDED,
}

/** A proposed change: the version that would apply, and who proposed it (INV-REQ-1007). */
data class PendingChange(
    val changeRef: String,
    val version: TripleVersion,
    val proposedBy: PersonRef,
    val state: ChangeState,
)

/**
 * The connection and the principal that submit a document. Held apart from the supplier triple: the resolver accepts
 * it, returns it beside the triple and never lets it reach a triple field (INV-REQ-803, INV-AC-2034).
 */
data class SubmittingIdentity(
    val connectionRef: String,
    val principalRef: String,
)

/** The catalogue code of an entity with no triple for its constitution (error-codes.yaml, owner Onboarding). */
const val MDM_005 = "MDM-005"

/** What resolving the triple of a document returns. */
sealed interface Resolution {
    /** The triple in force on the document's date, with the submitting identity as given, unchanged. */
    data class Resolved(
        val version: TripleVersion,
        val submitter: SubmittingIdentity?,
    ) : Resolution

    /** No triple is configured for the entity's constitution on that date: the document is blocked with MDM-005. */
    data class Blocked(
        val entity: EntityRef,
        val constitution: Constitution,
        val transactionDate: LocalDate,
        val submitter: SubmittingIdentity?,
    ) : Resolution {
        val code: String get() = MDM_005
    }
}

/** The triple in force on [date]: the version with the latest `effectiveFrom` that is not after it. */
fun List<TripleVersion>.inForceOn(date: LocalDate): TripleVersion? =
    filter { !it.effectiveFrom.isAfter(date) }.maxByOrNull { it.effectiveFrom }
