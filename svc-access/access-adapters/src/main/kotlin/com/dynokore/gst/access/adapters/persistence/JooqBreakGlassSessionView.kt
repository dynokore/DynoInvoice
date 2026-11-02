package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_SESSION
import com.dynokore.platform.serviceruntime.replica.BreakGlassSession
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.serviceruntime.replica.BreakGlassStatus
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset

/**
 * TPL-11. The session view of service-runtime's restricted break-glass mode, from access-svc's own table
 * `access.break_glass_session` (INV-Q-9298 B); it reads no replica. The restriction filter (order -47) runs inside the
 * binding of the end-user principal (order -50), so the tenant of the principal is bound, but no transaction is open
 * yet: the read opens its own, and the DSLContext, wired with the tenancy-jooq listeners, reads under forced
 * row-level security. A call for a tenant other than the bound
 * one answers null (fail closed for the lookup, never a read of another tenant).
 */
class JooqBreakGlassSessionView(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
) : BreakGlassSessionView {
    override fun restrictedSession(
        tenant: TenantId,
        actor: String,
        at: Instant,
    ): BreakGlassSession? {
        if (runCatching { tenants.current() }.getOrNull() != tenant) return null
        return tx.inTransaction { read(tenant, actor, at) }
    }

    private fun read(
        tenant: TenantId,
        actor: String,
        at: Instant,
    ): BreakGlassSession? =
        dsl
            .select(BREAK_GLASS_SESSION.UNLOCK_REF, BREAK_GLASS_SESSION.EXPIRES_AT, BREAK_GLASS_SESSION.APPROVED_BY)
            .from(BREAK_GLASS_SESSION)
            .where(BREAK_GLASS_SESSION.STATUS.ne(SEALED))
            .and(BREAK_GLASS_SESSION.EXPIRES_AT.gt(at.atOffset(ZoneOffset.UTC)))
            .and(DSL.value(actor).eq(DSL.any(BREAK_GLASS_SESSION.APPROVED_BY)))
            .orderBy(BREAK_GLASS_SESSION.EXPIRES_AT.desc(), BREAK_GLASS_SESSION.UNLOCK_REF.asc())
            .limit(1)
            .fetchOne()
            ?.let {
                BreakGlassSession(
                    unlockRef = checkNotNull(it.get(BREAK_GLASS_SESSION.UNLOCK_REF)),
                    tenant = tenant,
                    status = BreakGlassStatus.OPEN,
                    expiresAt = checkNotNull(it.get(BREAK_GLASS_SESSION.EXPIRES_AT)).toInstant(),
                    approvedBy = it.get(BREAK_GLASS_SESSION.APPROVED_BY).orEmpty().filterNotNull(),
                )
            }

    private companion object {
        const val SEALED = "SEALED"
    }
}
