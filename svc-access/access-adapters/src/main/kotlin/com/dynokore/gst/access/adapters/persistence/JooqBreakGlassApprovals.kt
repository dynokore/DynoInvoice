package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_APPROVAL
import com.dynokore.gst.access.application.breakglass.BreakGlassApproval
import com.dynokore.gst.access.application.breakglass.BreakGlassApprovals
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** TPL-11. The approvals of break-glass unlocks, in the bound tenant (the DSLContext adds `tenant_id`). */
class JooqBreakGlassApprovals(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : BreakGlassApprovals {
    override fun replace(
        approver: String,
        madeAt: Instant,
        window: Duration,
    ): BreakGlassApproval {
        val actor = actor()
        val ref = "BGP-" + ref()
        val made = madeAt.atOffset(ZoneOffset.UTC)
        // the unconsumed approval of this approver is replaced in place (the partial unique index allows one)
        val replaced =
            dsl
                .update(BREAK_GLASS_APPROVAL)
                .set(BREAK_GLASS_APPROVAL.APPROVAL_REF, ref)
                .set(BREAK_GLASS_APPROVAL.MADE_AT, made)
                .set(BREAK_GLASS_APPROVAL.UPDATED_AT, now())
                .set(BREAK_GLASS_APPROVAL.UPDATED_BY, actor)
                .set(BREAK_GLASS_APPROVAL.VERSION, BREAK_GLASS_APPROVAL.VERSION.plus(1L))
                .where(BREAK_GLASS_APPROVAL.APPROVER_REF.eq(approver))
                .and(BREAK_GLASS_APPROVAL.CONSUMED_AT.isNull)
                .execute()
        if (replaced == 0) {
            dsl
                .insertInto(BREAK_GLASS_APPROVAL)
                .set(BREAK_GLASS_APPROVAL.ID, ids.next(clock.instant()))
                .set(BREAK_GLASS_APPROVAL.APPROVAL_REF, ref)
                .set(BREAK_GLASS_APPROVAL.APPROVER_REF, approver)
                .set(BREAK_GLASS_APPROVAL.MADE_AT, made)
                .set(BREAK_GLASS_APPROVAL.CREATED_AT, now())
                .set(BREAK_GLASS_APPROVAL.CREATED_BY, actor)
                .set(BREAK_GLASS_APPROVAL.UPDATED_AT, now())
                .set(BREAK_GLASS_APPROVAL.UPDATED_BY, actor)
                .set(BREAK_GLASS_APPROVAL.VERSION, 0L)
                .execute()
        }
        return BreakGlassApproval(ref, tenants.current().toString(), approver, madeAt, madeAt.plus(window))
    }

    override fun consume(
        approvers: Collection<String>,
        after: Instant,
        unlockRef: String,
        consumedAt: Instant,
    ): Int {
        if (approvers.isEmpty()) return 0
        // exact equality on the approver; strictly later than the window start; one statement for all approvers
        return dsl
            .update(BREAK_GLASS_APPROVAL)
            .set(BREAK_GLASS_APPROVAL.CONSUMED_AT, consumedAt.atOffset(ZoneOffset.UTC))
            .set(BREAK_GLASS_APPROVAL.CONSUMED_UNLOCK_REF, unlockRef)
            .set(BREAK_GLASS_APPROVAL.UPDATED_AT, now())
            .set(BREAK_GLASS_APPROVAL.UPDATED_BY, actor())
            .set(BREAK_GLASS_APPROVAL.VERSION, BREAK_GLASS_APPROVAL.VERSION.plus(1L))
            .where(BREAK_GLASS_APPROVAL.APPROVER_REF.`in`(approvers))
            .and(BREAK_GLASS_APPROVAL.CONSUMED_AT.isNull)
            .and(BREAK_GLASS_APPROVAL.MADE_AT.gt(after.atOffset(ZoneOffset.UTC)))
            .returning(BREAK_GLASS_APPROVAL.APPROVER_REF)
            .fetch()
            .size
    }

    private fun ref(): String =
        ids
            .next(clock.instant())
            .toString()
            .replace("-", "")
            .takeLast(REF_DIGITS)
            .uppercase()

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }

    private companion object {
        const val REF_DIGITS = 8
    }
}
