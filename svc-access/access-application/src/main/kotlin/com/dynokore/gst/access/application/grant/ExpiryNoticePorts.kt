package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import java.time.Instant

/**
 * A grant the scan looks at: who granted it, the entity its notices are filed under (none when unknown) and whether
 * the grantee is external.
 */
data class ExpiringGrant(
    val grant: Grant,
    val grantedBy: IdentityRef,
    val filingEntity: EntityRef?,
    val external: Boolean,
)

/** The time-limited grants the scan must look at, including those that expired and have not raised ACC-003 yet. */
fun interface ExpiryScanSource {
    fun due(now: Instant): List<ExpiringGrant>
}

/** The expiry notices already sent and the ones recorded as superseded, as days before expiry, per grant. */
interface ExpiryNoticeLog {
    fun noticedDays(grant: GrantRef): Set<Int>

    fun supersededDays(grant: GrantRef): Set<Int>

    fun recordNoticed(
        grant: GrantRef,
        daysBefore: Int,
    )

    fun recordSuperseded(
        grant: GrantRef,
        daysBefore: Int,
    )
}

/** One expiry notice; no link and no passcode (INV-REQ-893 behaviour 3). */
data class ExpiryNoticeRequest(
    val template: String,
    val recipients: List<String>,
    val channel: ExpiryNoticeChannel,
    val entity: EntityRef,
) {
    init {
        require(recipients.size in 1..2 && recipients.toSet().size == recipients.size) {
            "an expiry notice goes to one or two distinct addresses"
        }
    }

    companion object {
        const val TEMPLATE = "grant-expiry-notice"
    }
}

enum class ExpiryNoticeChannel { EMAIL }

/** Requests the message (the platform event MessageRequested in layer 2). */
fun interface ExpiryNoticeMessages {
    fun request(request: ExpiryNoticeRequest)
}

/** The address of an identity, or null when none is known. Never called for a reserved invitation identity. */
fun interface GrantContacts {
    fun addressOf(identity: IdentityRef): String?
}

/** Whether ACC-003 was already raised for a grant. */
interface ExpiryAlertLog {
    fun wasRaised(grant: GrantRef): Boolean

    fun markRaised(grant: GrantRef)
}

/**
 * Metric and log lines for an unresolved recipient or filing entity; they carry the grant reference only, never an
 * address or a name.
 */
interface ExpiryNoticeTelemetry {
    fun recipientUnresolved(grantReference: String)

    /** A due grant whose source gives no filing entity is skipped; this makes the skip visible. */
    fun filingEntityUnresolved(grantReference: String)
}
