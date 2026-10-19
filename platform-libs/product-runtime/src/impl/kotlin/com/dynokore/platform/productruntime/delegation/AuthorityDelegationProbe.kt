package com.dynokore.platform.productruntime.delegation

import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentKey
import com.dynokore.platform.serviceruntime.replica.ConsentState
import java.time.Instant
import java.time.LocalDate

/**
 * config-resolver's [DelegationProbe] over service-runtime's authority replica (INV-Q-3136, INV-REQ-853): an entity
 * operates under delegation on a date when the replica holds a LIVE agent consent for it as a client entity whose
 * validity overlaps any instant of that Asia/Singapore business day (coordinator ruling INV-Q-3854 (i)). A
 * client-entity scope is delegated by definition.
 *
 * The answer fails towards delegation, which adds the UNDER_DELEGATION acknowledgers of a relaxation and never
 * removes one (INV-REQ-1214): with no authority replica bound, or a replica whose lag is unknown or beyond its
 * bound, the probe answers delegated. A target with no entity (installation, tenant, group) is not delegated:
 * consent is granted per client entity.
 */
class AuthorityDelegationProbe(
    private val replica: () -> AuthorityReplica?,
) : DelegationProbe {
    override fun isDelegated(
        target: SettingTarget,
        onDate: LocalDate,
    ): Boolean {
        if (target.ref.level == ScopeLevel.CLIENT_ENTITY) return true
        val authority = replica()
        return authority == null || !authority.lag().isWithin(authority.lagBound) ||
            hasLiveConsent(authority, target, onDate)
    }

    private fun hasLiveConsent(
        authority: AuthorityReplica,
        target: SettingTarget,
        onDate: LocalDate,
    ): Boolean {
        val tenant = target.tenant
        val entity = target.entity
        val consent =
            if (tenant != null &&
                entity != null
            ) {
                authority.consents.peek(ConsentKey(tenant, entity))
            } else {
                null
            }
        return consent != null && consent.state == ConsentState.LIVE && consent.overlaps(onDate)
    }

    private fun AuthorityRecord.ConsentRecord.overlaps(day: LocalDate): Boolean {
        val dayStart: Instant = day.atStartOfDay(BusinessDate.ZONE).toInstant()
        val nextDayStart: Instant = day.plusDays(1).atStartOfDay(BusinessDate.ZONE).toInstant()
        val until = validUntil
        return validFrom.isBefore(nextDayStart) && (until == null || until.isAfter(dayStart))
    }
}
