package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AcknowledgementClaim
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.DisableDecision
import com.dynokore.gst.notification.domain.alerting.DisableRule
import com.dynokore.gst.notification.domain.alerting.RecipientDecision
import com.dynokore.gst.notification.domain.alerting.RecipientRule
import com.dynokore.gst.notification.domain.alerting.RecordedAcknowledgement
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

data class SetAlertPolicyCommand(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val alertClass: AlertClass,
    val recipients: List<String>,
    val channels: List<String>,
    val acknowledgement: AcknowledgementClaim?,
    val businessDate: LocalDate,
)

sealed interface SetAlertPolicyResult {
    data class Forbidden(
        val reason: DecisionReason,
    ) : SetAlertPolicyResult

    data class Refused(
        val code: String,
        val reason: String,
    ) : SetAlertPolicyResult

    data class Accepted(
        val acknowledgement: RecordedAcknowledgement?,
    ) : SetAlertPolicyResult
}

/**
 * INV-REQ-1114, INV-REQ-1115: the permission decided by the replica comes first (INV-Q-10027), then the disable rule,
 * then the recipient rule. The command stores nothing; recording the policy is the policy-store slice.
 */
class SetAlertPolicy(
    private val replica: AuthorityReplica,
) {
    fun handle(
        command: SetAlertPolicyCommand,
        deadline: Deadline,
    ): SetAlertPolicyResult {
        val decision =
            replica.decide(
                AuthorizationRequest(
                    command.tenant,
                    command.principal,
                    command.entityRef,
                    AlertActions.SET_ALERT_POLICY,
                ),
                deadline,
            )
        return if (decision.allowed) applyRules(command) else SetAlertPolicyResult.Forbidden(decision.reason)
    }

    private fun applyRules(command: SetAlertPolicyCommand): SetAlertPolicyResult {
        val disable = DisableRule.decide(command.alertClass, command.channels)
        if (disable is DisableDecision.Refused) {
            return SetAlertPolicyResult.Refused(disable.code, "NOT_DISABLEABLE")
        }
        return when (
            val recipients = RecipientRule.decide(command.recipients, command.acknowledgement, command.businessDate)
        ) {
            is RecipientDecision.Refused -> SetAlertPolicyResult.Refused(recipients.code, recipients.reason.name)
            is RecipientDecision.Saved -> SetAlertPolicyResult.Accepted(recipients.acknowledgement)
        }
    }
}
