package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.BREAK_GLASS_SIGNIN_COUNTER
import com.dynokore.gst.access.application.breakglass.SignInAttempts
import com.dynokore.gst.access.domain.breakglass.SignInBackoff
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. The sign-in back-off state of one unlock of the bound tenant (INV-AC-9184). The state is read with a row
 * lock (`SELECT ... FOR UPDATE`) inside the transaction of the use case, so two concurrent wrong credentials for one
 * unlock are counted in turn. A missing row is the initial state.
 */
class JooqSignInAttempts(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
) : SignInAttempts {
    override fun readForUpdate(unlockRef: String): SignInBackoff {
        val row =
            dsl
                .select(BREAK_GLASS_SIGNIN_COUNTER.FAILURES, BREAK_GLASS_SIGNIN_COUNTER.NEXT_TRY_AT)
                .from(BREAK_GLASS_SIGNIN_COUNTER)
                .where(BREAK_GLASS_SIGNIN_COUNTER.TENANT_ID.eq(tenants.current().value))
                .and(BREAK_GLASS_SIGNIN_COUNTER.UNLOCK_REF.eq(unlockRef))
                .forUpdate()
                .fetchOne() ?: return SignInBackoff.INITIAL
        return SignInBackoff(
            checkNotNull(row.get(BREAK_GLASS_SIGNIN_COUNTER.FAILURES)),
            checkNotNull(row.get(BREAK_GLASS_SIGNIN_COUNTER.NEXT_TRY_AT)).toInstant(),
        )
    }

    override fun save(
        unlockRef: String,
        state: SignInBackoff,
    ) {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val nextTry = state.nextTryAt.atOffset(ZoneOffset.UTC)
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(BREAK_GLASS_SIGNIN_COUNTER)
            .set(BREAK_GLASS_SIGNIN_COUNTER.TENANT_ID, tenants.current().value)
            .set(BREAK_GLASS_SIGNIN_COUNTER.UNLOCK_REF, unlockRef)
            .set(BREAK_GLASS_SIGNIN_COUNTER.FAILURES, state.failures)
            .set(BREAK_GLASS_SIGNIN_COUNTER.NEXT_TRY_AT, nextTry)
            .set(BREAK_GLASS_SIGNIN_COUNTER.UPDATED_AT, now)
            .set(BREAK_GLASS_SIGNIN_COUNTER.UPDATED_BY, actor)
            .onConflict(BREAK_GLASS_SIGNIN_COUNTER.TENANT_ID, BREAK_GLASS_SIGNIN_COUNTER.UNLOCK_REF)
            .doUpdate()
            .set(BREAK_GLASS_SIGNIN_COUNTER.FAILURES, state.failures)
            .set(BREAK_GLASS_SIGNIN_COUNTER.NEXT_TRY_AT, nextTry)
            .set(BREAK_GLASS_SIGNIN_COUNTER.UPDATED_AT, now)
            .set(BREAK_GLASS_SIGNIN_COUNTER.UPDATED_BY, actor)
            .execute()
    }
}
