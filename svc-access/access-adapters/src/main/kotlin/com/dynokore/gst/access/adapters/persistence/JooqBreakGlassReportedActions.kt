package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_ACTION
import com.dynokore.gst.access.application.breakglass.BreakGlassReportedActions
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. Break-glass actions reported by another service as an event, held once per report id in the bound tenant. */
class JooqBreakGlassReportedActions(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : BreakGlassReportedActions {
    override fun find(reportId: String): String? =
        dsl
            .select(BREAK_GLASS_ACTION.ACTION_REF)
            .from(BREAK_GLASS_ACTION)
            .where(BREAK_GLASS_ACTION.REPORT_REF.eq(reportId))
            .fetchOne(BREAK_GLASS_ACTION.ACTION_REF)

    override fun store(
        unlockRef: String,
        action: BreakGlassAction,
        target: String?,
        callerService: String,
        reportId: String,
    ): String? {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val ref = "BGA-" + ref()
        val written =
            dsl
                .insertInto(BREAK_GLASS_ACTION)
                .set(BREAK_GLASS_ACTION.ID, ids.next(clock.instant()))
                .set(BREAK_GLASS_ACTION.ACTION_REF, ref)
                .set(BREAK_GLASS_ACTION.UNLOCK_REF, unlockRef)
                .set(BREAK_GLASS_ACTION.ACTION, action.name)
                .set(BREAK_GLASS_ACTION.TARGET, target)
                .set(BREAK_GLASS_ACTION.CALLER_SERVICE, callerService)
                .set(BREAK_GLASS_ACTION.REPORT_REF, reportId)
                .set(BREAK_GLASS_ACTION.CREATED_AT, now)
                .set(BREAK_GLASS_ACTION.CREATED_BY, actor)
                .set(BREAK_GLASS_ACTION.UPDATED_AT, now)
                .set(BREAK_GLASS_ACTION.UPDATED_BY, actor)
                .set(BREAK_GLASS_ACTION.VERSION, 0L)
                .onConflict(BREAK_GLASS_ACTION.TENANT_ID, BREAK_GLASS_ACTION.REPORT_REF)
                .doNothing()
                .execute()
        return if (written == 0) null else ref
    }

    private fun ref(): String =
        ids
            .next(clock.instant())
            .toString()
            .replace("-", "")
            .takeLast(REF_DIGITS)
            .uppercase()

    private companion object {
        const val REF_DIGITS = 8
    }
}
