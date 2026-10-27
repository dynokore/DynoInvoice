package com.dynokore.gst.submission.domain.typing

/** The `direction` members of canonical/openapi.yaml `RecordDocumentRequest`. */
enum class Direction { SALE, PURCHASE }

/** How the document travelled; a local vocabulary of the routing facts (INV-REQ-408, INV-REQ-406). */
enum class NetworkPath { ISSUED_VIA_NETWORK, RELAY_ISSUANCE, RECEIVED_VIA_NETWORK, NOT_ON_NETWORK }

/** The `submissionType` members of rules/schemas/scenario-pack-handlers.schema.json `$defs.party`. */
enum class SubmissionType { TYPE_1A, TYPE_1B, TYPE_2, TYPE_3 }

/** Why no type is decided. */
enum class UndecidedReason { CONTRADICTORY_FACTS, SELF_BILLING_DIRECTION_MISMATCH, FACTS_MISSING }

/** The party assignment a self-billed document carries from the scenario pack of rules-svc (INV-REQ-416). */
data class SelfBillingAssignment(
    val direction: Direction,
    val submissionType: SubmissionType,
)

/** The plain facts of one document version that the submission type depends on. */
data class RoutingFacts(
    val direction: Direction,
    val networkPath: NetworkPath,
    val counterpartyOnNetwork: Boolean?,
    val debitNote: Boolean,
    val selfBilling: SelfBillingAssignment?,
)

/** The decision of [SubmissionTypeDecision.decide]. */
sealed interface TypeDecision {
    data class Decided(
        val type: SubmissionType,
    ) : TypeDecision

    data class Undecided(
        val reason: UndecidedReason,
    ) : TypeDecision
}

/** Derivation step 9: the submission type of a document version (INV-REQ-415, -406, -408, -373). */
object SubmissionTypeDecision {
    /** The first row that applies wins: missing facts, debit note on the network, self-billing, then the path rows. */
    fun decide(facts: RoutingFacts?): TypeDecision =
        when {
            facts == null -> {
                TypeDecision.Undecided(UndecidedReason.FACTS_MISSING)
            }

            facts.debitNote && isNetworkTransmission(facts.networkPath) -> {
                TypeDecision.Undecided(UndecidedReason.CONTRADICTORY_FACTS)
            }

            facts.selfBilling != null -> {
                decideSelfBilled(facts.direction, facts.selfBilling)
            }

            else -> {
                decideByPath(facts)
            }
        }

    private fun isNetworkTransmission(path: NetworkPath) =
        path == NetworkPath.ISSUED_VIA_NETWORK || path == NetworkPath.RECEIVED_VIA_NETWORK

    private fun decideSelfBilled(
        direction: Direction,
        assignment: SelfBillingAssignment,
    ): TypeDecision =
        if (assignment.direction == direction) {
            TypeDecision.Decided(assignment.submissionType)
        } else {
            TypeDecision.Undecided(UndecidedReason.SELF_BILLING_DIRECTION_MISMATCH)
        }

    private fun decideByPath(facts: RoutingFacts): TypeDecision =
        when (facts.direction to facts.networkPath) {
            Direction.SALE to NetworkPath.ISSUED_VIA_NETWORK -> {
                TypeDecision.Decided(SubmissionType.TYPE_1A)
            }

            Direction.SALE to NetworkPath.RELAY_ISSUANCE -> {
                TypeDecision.Decided(
                    if (facts.counterpartyOnNetwork == true && !facts.debitNote) {
                        SubmissionType.TYPE_1A
                    } else {
                        SubmissionType.TYPE_2
                    },
                )
            }

            Direction.PURCHASE to NetworkPath.RECEIVED_VIA_NETWORK -> {
                TypeDecision.Decided(SubmissionType.TYPE_1B)
            }

            Direction.SALE to NetworkPath.NOT_ON_NETWORK -> {
                TypeDecision.Decided(SubmissionType.TYPE_2)
            }

            Direction.PURCHASE to NetworkPath.NOT_ON_NETWORK -> {
                TypeDecision.Decided(SubmissionType.TYPE_3)
            }

            else -> {
                TypeDecision.Undecided(UndecidedReason.CONTRADICTORY_FACTS)
            }
        }
}
