package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FilingGateOverrideAudit
import com.dynokore.gst.submission.application.gate.AuditTrail
import com.dynokore.gst.submission.application.gate.StoredOverride
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Instant

/** The request and release instants of a stored override (the contract's `requestedAt` and `releasedAt`). */
data class OverrideTimes(
    val requestedAt: Instant,
    val releasedAt: Instant?,
)

/** The instants of the overrides of one period, by check ID. */
fun interface OverrideTimeSource {
    fun ofPeriod(
        entityRef: String,
        periodRef: String,
    ): Map<String, OverrideTimes>
}

/**
 * [AuditTrail] over `submission.filing_gate_override_audit` (INV-REQ-500, NIST SP 800-92, SP 800-53 AU-2, AU-3, AU-9):
 * insert only, in the caller's open transaction, so an entry never outlives a rolled-back call and is never changed or
 * deleted (the runtime role holds no update or delete). Entries name the acting user ID, never a role (INV-Q-10647).
 */
class JooqFilingGateAuditTrail(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : AuditTrail,
    OverrideTimeSource {
    private val t = FilingGateOverrideAudit.FILING_GATE_OVERRIDE_AUDIT

    override fun requested(
        userId: String,
        override: StoredOverride,
    ) = append(ACTION_REQUESTED, userId, override, firstApprover = null, reason = override.reason)

    override fun approved(
        userId: String,
        override: StoredOverride,
    ) = append(ACTION_APPROVED, userId, override, firstApprover = override.requestedBy, reason = null)

    override fun released(
        userId: String,
        override: StoredOverride,
    ) = append(ACTION_RELEASED, userId, override, firstApprover = override.requestedBy, reason = null)

    /**
     * `requestedAt` is the `recorded_at` of the REQUESTED entry and `releasedAt` that of the RELEASED entry of the same
     * (tenant, entity, period, check). A request without its REQUESTED entry, or a release without its RELEASED entry,
     * is not invented: the read fails (fail closed). A check with only an APPROVED entry has no instant of its own.
     */
    override fun ofPeriod(
        entityRef: String,
        periodRef: String,
    ): Map<String, OverrideTimes> {
        val rows =
            dsl
                .select(t.CHECK_ID, t.ACTION, t.RECORDED_AT)
                .from(t)
                .where(t.TENANT_ID.eq(tenants.current().value))
                .and(t.ENTITY_REF.eq(entityRef))
                .and(t.PERIOD_REF.eq(periodRef))
                .and(t.ACTION.`in`(ACTION_REQUESTED, ACTION_RELEASED))
                .fetch()
        val requested =
            rows.filter { it.get(t.ACTION) == ACTION_REQUESTED }.associate {
                checkNotNull(it.get(t.CHECK_ID)) to
                    it
            }
        val released =
            rows.filter { it.get(t.ACTION) == ACTION_RELEASED }.associate {
                checkNotNull(it.get(t.CHECK_ID)) to
                    it
            }
        return requested.mapValues { (checkId, row) ->
            OverrideTimes(
                checkNotNull(row.get(t.RECORDED_AT)).toInstant(),
                released[checkId]?.let { checkNotNull(it.get(t.RECORDED_AT)).toInstant() },
            )
        }
    }

    private fun append(
        action: String,
        userId: String,
        override: StoredOverride,
        firstApprover: String?,
        reason: String?,
    ) {
        dsl
            .insertInto(t)
            .set(t.TENANT_ID, tenants.current().value)
            .set(t.ENTITY_REF, override.entityRef)
            .set(t.PERIOD_REF, override.periodRef)
            .set(t.CHECK_ID, override.checkId)
            .set(t.ACTION, action)
            .set(t.ACTOR_USER_ID, userId)
            .set(t.FIRST_APPROVER_USER_ID, firstApprover)
            .set(t.REASON, reason)
            .execute()
    }

    private companion object {
        const val ACTION_REQUESTED = "REQUESTED"
        const val ACTION_APPROVED = "APPROVED"
        const val ACTION_RELEASED = "RELEASED"
    }
}
