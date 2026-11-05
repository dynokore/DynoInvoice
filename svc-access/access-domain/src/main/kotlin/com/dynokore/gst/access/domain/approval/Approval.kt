package com.dynokore.gst.access.domain.approval

import com.dynokore.gst.access.domain.grant.IdentityRef
import java.math.BigDecimal

/** The values of VP-03 and VP-04: who approves a mapping pack (INV-REQ-1264, INV-REQ-1265). */
enum class PackApprovalOption {
    STAFF_APPROVER,
    EXTERNAL_ADVISOR,
    NON_FINANCE_OFFICER,
    SOLE_OPERATOR_ACK,
    AGENT_SECOND_PERSON_CLIENT_ACK,
    AGENT_SECOND_PERSON_CLIENT_APPROVAL,
    CLIENT_APPROVER_ALONE,
}

/** The values of VP-32: who approves an override (INV-REQ-1293). */
enum class OverrideReleaseOption {
    ONE_TAX_APPROVER,
    SECOND_APPROVER_ABOVE_AMOUNT,
    SECOND_APPROVER_ALWAYS,
}

/** What an approval still waits for after the approver's decision. */
enum class Outstanding { CLIENT_ACKNOWLEDGEMENT, CLIENT_APPROVAL }

enum class PackDenyReason {
    AUTHOR_IS_APPROVER,
    NOT_STAFF,
    NOT_EXTERNAL,
    NO_SOLE_OPERATOR_ACK,
    NOT_AGENT_SECOND_PERSON,
    NOT_CLIENT_APPROVER,
}

/**
 * What the grants of the approver say about the pack's entity, read live from the grant table: the facts the rules
 * below compare, never a role name (INV-Q-10027).
 *  - [staff]: a covering grant of the permission to approve, held by a person of the tenant's own staff;
 *  - [externalOnEntity]: a covering grant of that permission, held by an external person, scoped to the entity;
 *  - [agentSecondPerson]: a covering grant of that permission whose scope is consent-derived (INV-T-4939);
 *  - [clientOwnApprover]: a covering grant of that permission, not consent-derived, held by the client's own staff.
 */
data class ApproverStanding(
    val staff: Boolean = false,
    val externalOnEntity: Boolean = false,
    val agentSecondPerson: Boolean = false,
    val clientOwnApprover: Boolean = false,
)

/** The reference of the tenant's ACTIVE sole-operator acknowledgement, or null when there is none (INV-REQ-994). */
@JvmInline
value class AckRef(
    val value: String,
)

sealed interface PackVerdict {
    data class Allow(
        val soleOperatorAck: AckRef? = null,
        val outstanding: List<Outstanding> = emptyList(),
    ) : PackVerdict

    data class Deny(
        val reason: PackDenyReason,
    ) : PackVerdict
}

/**
 * The separation of duty on the approval of a mapping pack (INV-Q-10695 R3; INV-REQ-860, 993, 995, 996). Pure: the
 * person is compared by equality of the whole reference (no trimming, no case folding), and an option or a fact it does
 * not know allows nothing.
 */
object PackApprovalRules {
    @Suppress("LongParameterList") // the question of INV-T-4936: option, two people, standing, acknowledgement, prior
    fun decide(
        option: PackApprovalOption,
        author: IdentityRef,
        approver: IdentityRef,
        standing: ApproverStanding,
        activeAck: AckRef?,
        priorAgentApproval: Boolean,
    ): PackVerdict {
        val sameAsAuthor = approver == author
        return when (option) {
            PackApprovalOption.STAFF_APPROVER, PackApprovalOption.NON_FINANCE_OFFICER -> {
                distinct(sameAsAuthor, standing.staff, PackDenyReason.NOT_STAFF)
            }

            PackApprovalOption.EXTERNAL_ADVISOR -> {
                distinct(sameAsAuthor, standing.externalOnEntity, PackDenyReason.NOT_EXTERNAL)
            }

            PackApprovalOption.SOLE_OPERATOR_ACK -> {
                soleOperator(sameAsAuthor, activeAck)
            }

            PackApprovalOption.AGENT_SECOND_PERSON_CLIENT_ACK -> {
                agentSecond(sameAsAuthor, standing, listOf(Outstanding.CLIENT_ACKNOWLEDGEMENT))
            }

            PackApprovalOption.AGENT_SECOND_PERSON_CLIENT_APPROVAL -> {
                clientApproval(sameAsAuthor, standing, priorAgentApproval)
            }

            PackApprovalOption.CLIENT_APPROVER_ALONE -> {
                when {
                    !standing.clientOwnApprover -> PackVerdict.Deny(PackDenyReason.NOT_CLIENT_APPROVER)
                    sameAsAuthor -> PackVerdict.Deny(PackDenyReason.AUTHOR_IS_APPROVER)
                    else -> PackVerdict.Allow()
                }
            }
        }
    }

    /** A person other than the author who has the standing the option asks for. */
    private fun distinct(
        sameAsAuthor: Boolean,
        hasStanding: Boolean,
        otherwise: PackDenyReason,
    ): PackVerdict =
        when {
            sameAsAuthor -> PackVerdict.Deny(PackDenyReason.AUTHOR_IS_APPROVER)
            hasStanding -> PackVerdict.Allow()
            else -> PackVerdict.Deny(otherwise)
        }

    private fun soleOperator(
        sameAsAuthor: Boolean,
        activeAck: AckRef?,
    ): PackVerdict =
        when {
            !sameAsAuthor -> PackVerdict.Allow()
            activeAck != null -> PackVerdict.Allow(soleOperatorAck = activeAck)
            else -> PackVerdict.Deny(PackDenyReason.NO_SOLE_OPERATOR_ACK)
        }

    private fun clientApproval(
        sameAsAuthor: Boolean,
        standing: ApproverStanding,
        priorAgentApproval: Boolean,
    ): PackVerdict =
        when {
            sameAsAuthor -> PackVerdict.Deny(PackDenyReason.AUTHOR_IS_APPROVER)
            standing.agentSecondPerson -> PackVerdict.Allow(outstanding = listOf(Outstanding.CLIENT_APPROVAL))
            standing.clientOwnApprover && priorAgentApproval -> PackVerdict.Allow()
            else -> PackVerdict.Deny(PackDenyReason.NOT_AGENT_SECOND_PERSON)
        }

    private fun agentSecond(
        sameAsAuthor: Boolean,
        standing: ApproverStanding,
        outstanding: List<Outstanding>,
    ): PackVerdict =
        when {
            sameAsAuthor -> PackVerdict.Deny(PackDenyReason.AUTHOR_IS_APPROVER)
            standing.agentSecondPerson -> PackVerdict.Allow(outstanding = outstanding)
            else -> PackVerdict.Deny(PackDenyReason.NOT_AGENT_SECOND_PERSON)
        }
}

enum class OverrideDenyReason { NOT_OVERRIDABLE, SAME_PERSON_TWICE, NO_SOLE_OPERATOR_ACK }

enum class OverrideOutcome { RELEASED, AWAITS_SECOND_APPROVER }

/** The value of VP-32 in force: the option, and the typed amount of SECOND_APPROVER_ABOVE_AMOUNT in SGD. */
data class OverridePolicy(
    val option: OverrideReleaseOption,
    val thresholdSgd: BigDecimal?,
) {
    init {
        require((option == OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT) == (thresholdSgd != null)) {
            "the amount belongs to SECOND_APPROVER_ABOVE_AMOUNT and to no other option"
        }
        require(thresholdSgd == null || thresholdSgd.signum() > 0) { "the amount is above 0" }
    }
}

/** The document a validation override is for; a filing-gate override has none (INV-Q-10721). */
data class OverrideDocument(
    val ref: String,
    val version: Int,
    val totalSgd: BigDecimal,
)

sealed interface OverrideVerdict {
    data class Allow(
        val outcome: OverrideOutcome,
        val soleOperatorAck: AckRef? = null,
    ) : OverrideVerdict

    data class Deny(
        val reason: OverrideDenyReason,
    ) : OverrideVerdict
}

/** What decides an override apart from the policy: who asks, who approved before, and the code's disposition. */
data class OverrideRequest(
    val overridable: Boolean,
    val document: OverrideDocument?,
    val approver: IdentityRef,
    val priorApprovers: List<IdentityRef>,
    val managesOverridePolicy: Boolean,
    val activeAck: AckRef?,
)

/** The release of an override (INV-Q-10695 R4; INV-REQ-1293, INV-REQ-500, INV-Q-10721). Pure. */
object OverrideReleaseRules {
    fun decide(
        policy: OverridePolicy,
        request: OverrideRequest,
    ): OverrideVerdict =
        when {
            !request.overridable -> {
                OverrideVerdict.Deny(OverrideDenyReason.NOT_OVERRIDABLE)
            }

            request.approver in request.priorApprovers -> {
                OverrideVerdict.Deny(OverrideDenyReason.SAME_PERSON_TWICE)
            }

            request.managesOverridePolicy && request.activeAck == null -> {
                OverrideVerdict.Deny(OverrideDenyReason.NO_SOLE_OPERATOR_ACK)
            }

            else -> {
                val ack = if (request.managesOverridePolicy) request.activeAck else null
                OverrideVerdict.Allow(outcomeOf(policy, request), ack)
            }
        }

    private fun outcomeOf(
        policy: OverridePolicy,
        request: OverrideRequest,
    ): OverrideOutcome {
        val second = request.priorApprovers.isNotEmpty()
        val needsSecond =
            when (policy.option) {
                OverrideReleaseOption.ONE_TAX_APPROVER -> {
                    false
                }

                OverrideReleaseOption.SECOND_APPROVER_ALWAYS -> {
                    true
                }

                // no total (a filing-gate override) fails closed, whatever the typed amount (INV-Q-10721)
                OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT -> {
                    val total = request.document?.totalSgd
                    total == null || total.compareTo(checkNotNull(policy.thresholdSgd)) > 0
                }
            }
        return if (needsSecond && !second) OverrideOutcome.AWAITS_SECOND_APPROVER else OverrideOutcome.RELEASED
    }
}
