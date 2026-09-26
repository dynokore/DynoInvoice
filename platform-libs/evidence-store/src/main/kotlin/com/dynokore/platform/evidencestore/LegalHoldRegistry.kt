package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantId

/**
 * Where a (tenant, entity, period) legal hold is placed and released
 * (FR-D.4). Who may call [place]/[release] is an authorisation decision this
 * kernel module does not make (ARC-16: no role vocabulary in L1) — that
 * belongs to whichever service wires this port to its own `Authorizer`
 * (TPL-04), such as the future evidence-svc's `tax.approver`/`security.officer`
 * flow.
 */
interface LegalHoldRegistry {
    fun place(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        hold: LegalHold,
    )

    fun release(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    )

    fun isHeld(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Boolean
}
