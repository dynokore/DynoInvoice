package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.SnoozeDecision
import com.dynokore.gst.notification.domain.alerting.SnoozeRule
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant

/** The hard stop (due date minus the safety margin) of an alert; null when it cannot be determined. */
fun interface HardStopSource {
    fun hardStopOf(
        tenant: TenantId,
        entityRef: String,
        alertId: String,
    ): Instant?
}

data class SnoozeAlertCommand(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val alertId: String,
    val alertClass: AlertClass,
    val snoozeUntil: Instant,
)

sealed interface SnoozeAlertResult {
    data class Forbidden(
        val reason: DecisionReason,
    ) : SnoozeAlertResult

    data class Refused(
        val code: String,
        val reason: String,
    ) : SnoozeAlertResult

    data class Accepted(
        val until: Instant,
    ) : SnoozeAlertResult
}

/** INV-REQ-1114: the decided permission first (INV-Q-10027); the clock and hard stop are read only after it. */
class SnoozeAlert(
    private val replica: AuthorityReplica,
    private val clock: Clock,
    private val hardStops: HardStopSource,
) {
    fun handle(
        command: SnoozeAlertCommand,
        deadline: Deadline,
    ): SnoozeAlertResult {
        val decision =
            replica.decide(
                AuthorizationRequest(command.tenant, command.principal, command.entityRef, AlertActions.SNOOZE_ALERT),
                deadline,
            )
        if (!decision.allowed) return SnoozeAlertResult.Forbidden(decision.reason)
        val hardStop =
            if (command.alertClass == AlertClass.DEADLINE || command.alertClass == AlertClass.COVERAGE) {
                hardStops.hardStopOf(command.tenant, command.entityRef, command.alertId)
            } else {
                null
            }
        return when (
            val snooze =
                SnoozeRule.decide(
                    command.alertClass,
                    clock.instant(),
                    command.snoozeUntil,
                    hardStop,
                )
        ) {
            is SnoozeDecision.Refused -> SnoozeAlertResult.Refused(snooze.code, snooze.reason.name)
            is SnoozeDecision.Accepted -> SnoozeAlertResult.Accepted(snooze.until)
        }
    }
}
