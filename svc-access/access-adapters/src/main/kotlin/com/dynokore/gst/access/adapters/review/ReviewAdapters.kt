package com.dynokore.gst.access.adapters.review

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.review.CadenceAnswer
import com.dynokore.gst.access.application.review.ReviewAudit
import com.dynokore.gst.access.application.review.ReviewAuditEntry
import com.dynokore.gst.access.application.review.ReviewCadenceSource
import com.dynokore.gst.access.application.review.ReviewRevocations
import com.dynokore.gst.access.application.review.Vp05Availability
import com.dynokore.gst.access.application.review.VpAvailability
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock

/** The variation point of the review cadence (INV-REQ-1266). */
internal val VP_05 = VariationPointKey("VP-05")

/**
 * VP-05's availability read from the catalogue the service runs with, on every call. Only an `available` row answers
 * AVAILABLE; a planned, retired or unknown row answers PLANNED, so monthly is never opened by a failed lookup
 * (INV-REQ-1266, fail closed).
 */
class CatalogueVp05Availability(
    private val catalogue: VariationPointCatalogue,
) : Vp05Availability {
    override fun current(): VpAvailability =
        if (catalogue.find(VP_05)?.status == VpStatus.AVAILABLE) VpAvailability.AVAILABLE else VpAvailability.PLANNED
}

/**
 * The tenant's stored VP-05 value as `setPolicy` of service-runtime stores it, read through [PolicyReader] at tenant
 * scope the way `VariationPointApprovalSettings` reads VP-03: QUARTERLY when none is set (the catalogue default), and
 * UNREADABLE on any read failure, on a selection that is not exactly one known option, and on an option this service
 * does not know (exact string equality), so no other value answers in their place.
 */
class VariationPointReviewCadence(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
) : ReviewCadenceSource {
    override fun current(): CadenceAnswer =
        runCatching {
            val selected =
                reader
                    .resolve(
                        VP_05,
                        PolicyScope.tenant(tenants.current()),
                        BusinessDate.today(clock),
                    ).selected
            when (selected.singleOrNull()?.key) {
                "QUARTERLY" -> CadenceAnswer.QUARTERLY
                "MONTHLY" -> CadenceAnswer.MONTHLY
                else -> CadenceAnswer.UNREADABLE
            }
        }.getOrDefault(CadenceAnswer.UNREADABLE)
}

/**
 * Revokes the grants a closed campaign marks REVOKE (INV-REQ-894 behaviour 3) through [RevokeGrantUseCase]: the same
 * grant update, AuthorityChanged outbox row and audit entry as `revokeGrant`. It joins the open transaction of the
 * closing outcome (the transaction runner joins a transaction of the same binding), so a failure anywhere rolls the
 * outcome, the status and every grant back together. A grant that is already revoked is left as it is.
 */
class GrantRevokingReviewRevocations(
    private val revoke: RevokeGrantUseCase,
) : ReviewRevocations {
    override fun revoke(
        campaign: String,
        grants: Set<GrantRef>,
        actor: IdentityRef,
    ) {
        grants.sortedBy { it.value }.forEach { revoke.handle(actor.value, it) }
    }
}

/** Writes the review audit entry through the service's grant audit, in the command transaction. */
class GrantAuditReview(
    private val audit: GrantAudit,
) : ReviewAudit {
    override fun record(entry: ReviewAuditEntry) {
        audit.record(
            GrantAuditEntry(
                actor = entry.actor,
                action = entry.action,
                scope = "review:${entry.campaign}" + entry.grant?.let { ":$it" }.orEmpty(),
                reason = entry.ackRef?.let { "soleOperatorAck=$it" },
                priorStateJson = null,
            ),
        )
    }
}
