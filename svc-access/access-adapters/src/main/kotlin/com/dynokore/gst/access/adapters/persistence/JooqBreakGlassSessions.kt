package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_ACTION
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_SESSION
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.expiryOf
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** TPL-11. Break-glass sessions and the actions recorded under them, in the bound tenant. */
class JooqBreakGlassSessions(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : BreakGlassSessions {
    override fun insert(
        sessionHours: Int,
        unlockedAt: Instant,
        approvedBy: List<String>,
    ): BreakGlassSession {
        val actor = actor()
        val ref = "BG-" + ref()
        dsl
            .insertInto(BREAK_GLASS_SESSION)
            .set(BREAK_GLASS_SESSION.ID, ids.next(clock.instant()))
            .set(BREAK_GLASS_SESSION.UNLOCK_REF, ref)
            .set(BREAK_GLASS_SESSION.SESSION_HOURS, sessionHours)
            .set(BREAK_GLASS_SESSION.UNLOCKED_AT, unlockedAt.atOffset(ZoneOffset.UTC))
            .set(BREAK_GLASS_SESSION.EXPIRES_AT, expiryOf(unlockedAt, sessionHours).atOffset(ZoneOffset.UTC))
            .set(BREAK_GLASS_SESSION.APPROVED_BY, approvedBy.toTypedArray())
            .set(BREAK_GLASS_SESSION.STATUS, UNLOCKED)
            .set(BREAK_GLASS_SESSION.CREATED_AT, now())
            .set(BREAK_GLASS_SESSION.CREATED_BY, actor)
            .set(BREAK_GLASS_SESSION.UPDATED_AT, now())
            .set(BREAK_GLASS_SESSION.UPDATED_BY, actor)
            .set(BREAK_GLASS_SESSION.VERSION, 0L)
            .execute()
        return checkNotNull(find(ref)) { "the session just written is not readable" }
    }

    override fun find(ref: String): BreakGlassSession? =
        dsl
            .selectFrom(BREAK_GLASS_SESSION)
            .where(BREAK_GLASS_SESSION.UNLOCK_REF.eq(ref))
            .fetchOne()
            ?.let { toSession(it) }

    override fun insertAction(
        unlockRef: String,
        action: BreakGlassAction,
        target: String?,
        callerService: String,
    ): String {
        val ref = "BGA-" + ref()
        dsl
            .insertInto(BREAK_GLASS_ACTION)
            .set(BREAK_GLASS_ACTION.ID, ids.next(clock.instant()))
            .set(BREAK_GLASS_ACTION.ACTION_REF, ref)
            .set(BREAK_GLASS_ACTION.UNLOCK_REF, unlockRef)
            .set(BREAK_GLASS_ACTION.ACTION, action.name)
            .set(BREAK_GLASS_ACTION.TARGET, target)
            .set(BREAK_GLASS_ACTION.CALLER_SERVICE, callerService)
            .set(BREAK_GLASS_ACTION.CREATED_AT, now())
            .set(BREAK_GLASS_ACTION.CREATED_BY, actor())
            .set(BREAK_GLASS_ACTION.UPDATED_AT, now())
            .set(BREAK_GLASS_ACTION.UPDATED_BY, actor())
            .set(BREAK_GLASS_ACTION.VERSION, 0L)
            .execute()
        return ref
    }

    private fun toSession(row: Record) =
        BreakGlassSession(
            ref = row.get(BREAK_GLASS_SESSION.UNLOCK_REF).column("unlock_ref"),
            tenantRef = tenants.current().toString(),
            sessionHours = row.get(BREAK_GLASS_SESSION.SESSION_HOURS).column("session_hours"),
            unlockedAt = row.get(BREAK_GLASS_SESSION.UNLOCKED_AT).column("unlocked_at").toInstant(),
            expiresAt = row.get(BREAK_GLASS_SESSION.EXPIRES_AT).column("expires_at").toInstant(),
            approvedBy = row.get(BREAK_GLASS_SESSION.APPROVED_BY).column("approved_by").filterNotNull(),
            sealed = row.get(BREAK_GLASS_SESSION.STATUS) == SEALED,
        )

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
        const val UNLOCKED = "UNLOCKED"
        const val SEALED = "SEALED"
        const val REF_DIGITS = 8
    }
}
