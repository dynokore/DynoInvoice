package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_ACCOUNT
import com.dynokore.gst.access.application.breakglass.BreakGlassAccounts
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The sealed break-glass account of the bound tenant: one row, the digest of the sealing credential only. */
class JooqBreakGlassAccounts(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : BreakGlassAccounts {
    override fun insertIfAbsent(verifier: String): Boolean {
        val actor = actor()
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        return dsl
            .insertInto(BREAK_GLASS_ACCOUNT)
            .set(BREAK_GLASS_ACCOUNT.ID, ids.next(clock.instant()))
            .set(BREAK_GLASS_ACCOUNT.TENANT_ID, tenants.current().value)
            .set(BREAK_GLASS_ACCOUNT.VERIFIER, verifier)
            .set(BREAK_GLASS_ACCOUNT.CREATED_AT, now)
            .set(BREAK_GLASS_ACCOUNT.CREATED_BY, actor)
            .set(BREAK_GLASS_ACCOUNT.UPDATED_AT, now)
            .set(BREAK_GLASS_ACCOUNT.UPDATED_BY, actor)
            .set(BREAK_GLASS_ACCOUNT.VERSION, 0L)
            .onConflict(BREAK_GLASS_ACCOUNT.TENANT_ID)
            .doNothing()
            .execute() == 1
    }

    override fun verifier(): String? =
        dsl
            .select(BREAK_GLASS_ACCOUNT.VERIFIER)
            .from(BREAK_GLASS_ACCOUNT)
            .where(BREAK_GLASS_ACCOUNT.TENANT_ID.eq(tenants.current().value))
            .fetchOne(BREAK_GLASS_ACCOUNT.VERIFIER)

    override fun replaceVerifier(verifier: String): Boolean =
        dsl
            .update(BREAK_GLASS_ACCOUNT)
            .set(BREAK_GLASS_ACCOUNT.VERIFIER, verifier)
            .set(BREAK_GLASS_ACCOUNT.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(BREAK_GLASS_ACCOUNT.UPDATED_BY, actor())
            .set(BREAK_GLASS_ACCOUNT.VERSION, BREAK_GLASS_ACCOUNT.VERSION.plus(1L))
            .where(BREAK_GLASS_ACCOUNT.TENANT_ID.eq(tenants.current().value))
            .execute() == 1

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
}
