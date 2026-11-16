package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.application.invitation.InvitationIdentity
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantExpiryNotices
import com.dynokore.gst.access.domain.grant.GrantNoticeDecision
import java.time.Instant
import java.time.ZoneId

private const val DEFAULT_FAR_DAYS = 14
private const val DEFAULT_NEAR_DAYS = 3

/** The tenant's notice days (default 14 and 3), the bound of INV-REQ-1267 and the zone of the expiry date. */
data class ExpiryNoticeSettings(
    val days: List<Int> = listOf(DEFAULT_FAR_DAYS, DEFAULT_NEAR_DAYS),
    val minimumLeadDays: Int = DEFAULT_NEAR_DAYS,
    val zone: ZoneId = ZoneId.of("Asia/Singapore"),
)

/** Where the addresses come from and where an unresolved one is reported. */
class ExpiryRecipients(
    val contacts: GrantContacts,
    val telemetry: ExpiryNoticeTelemetry,
)

/** The ACC-003 raise and its once-only log. */
class ExpiryAlerting(
    val log: ExpiryAlertLog,
    val alerts: AccessAlerts,
)

/**
 * Scans the time-limited grants and requests their expiry notices (INV-REQ-893, INV-REQ-1267). Dates come from
 * [GrantExpiryNotices.schedule] and the catch-up decision from [GrantNoticeDecision.decide]; nothing is re-implemented.
 */
class ScanGrantExpiryNotices(
    private val source: ExpiryScanSource,
    private val log: ExpiryNoticeLog,
    private val messages: ExpiryNoticeMessages,
    private val recipients: ExpiryRecipients,
    private val alerting: ExpiryAlerting,
    private val settings: ExpiryNoticeSettings = ExpiryNoticeSettings(),
) {
    fun scan(now: Instant) {
        source.due(now).forEach { item ->
            val filing = item.filingEntity
            val until = item.grant.window.until
            when {
                filing == null || until == null -> Unit
                now.isBefore(until) -> notify(item, filing, now)
                else -> raiseExpired(item, filing)
            }
        }
    }

    private fun notify(
        item: ExpiringGrant,
        filing: EntityRef,
        now: Instant,
    ) {
        val grant = item.grant
        val schedule =
            GrantExpiryNotices.schedule(grant.window, settings.zone, settings.days, settings.minimumLeadDays) ?: return
        val recorded = log.noticedDays(grant.ref) + log.supersededDays(grant.ref)
        val decision = GrantNoticeDecision.decide(schedule, now.atZone(settings.zone).toLocalDate(), recorded)
        decision.superseded.forEach { log.recordSuperseded(grant.ref, it.daysBefore) }
        decision.send.forEach { notice ->
            val addresses = addressesOf(item)
            if (addresses.isNotEmpty()) {
                messages.request(
                    ExpiryNoticeRequest(ExpiryNoticeRequest.TEMPLATE, addresses, ExpiryNoticeChannel.EMAIL, filing),
                )
                log.recordNoticed(grant.ref, notice.daysBefore)
            }
        }
    }

    private fun addressesOf(item: ExpiringGrant): List<String> {
        val addresses = mutableListOf<String>()
        listOf(item.grant.identity, item.grantedBy).distinct().forEach { identity ->
            val address =
                if (InvitationIdentity.isReserved(
                        identity.value,
                    )
                ) {
                    null
                } else {
                    recipients.contacts.addressOf(identity)
                }
            if (address ==
                null
            ) {
                recipients.telemetry.recipientUnresolved(item.grant.ref.value)
            } else {
                addresses += address
            }
        }
        return addresses.distinct()
    }

    private fun raiseExpired(
        item: ExpiringGrant,
        filing: EntityRef,
    ) {
        if (!item.external || alerting.log.wasRaised(item.grant.ref)) return
        alerting.alerts.raise(ALERT_CODE, "entity:${filing.value}")
        alerting.log.markRaised(item.grant.ref)
    }

    private companion object {
        const val ALERT_CODE = "ACC-003"
    }
}
