package com.dynokore.gst.consoleBff.adapters.deferral

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.DEFERRAL
import com.dynokore.gst.consoleBff.application.deferral.DeferralStore
import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralKind
import com.dynokore.gst.consoleBff.domain.deferral.DeferralState
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.ZoneOffset
import java.util.UUID

/**
 * `console_bff.deferral` through the jOOQ DSL and the generated table class (TPL-11). The port takes no tenant: the
 * tenant is the caller's signed database binding (`console_bff.bound_tenant()`), which forced row level security
 * enforces and every statement here also names (defence in depth, NIST SP 800-53 AC-3). The undoing user is not stored.
 */
class JooqDeferralStore(
    private val dsl: DSLContext,
) : DeferralStore {
    // The exact sub-select form of the platform predicate (INV-Q-4121); evaluated once per statement.
    // Built from jOOQ's typed name and function API, not a SQL string (the raw-SQL placement gate refuses strings).
    private val boundTenant: Field<UUID?> =
        DSL.field(DSL.select(DSL.function(DSL.name("console_bff", "bound_tenant"), UUID::class.java)))

    /** Race-safe: the partial unique index arbitrates, then the winner's DEFERRED row is read back. */
    override fun deferIfAbsent(candidate: Deferral): Deferral {
        dsl
            .insertInto(DEFERRAL)
            .set(DEFERRAL.TENANT_ID, boundTenant)
            .set(DEFERRAL.DEFERRAL_ID, candidate.deferralId)
            .set(DEFERRAL.ENTITY_REF, candidate.entityRef)
            .set(DEFERRAL.TARGET_KIND, candidate.target.kind.name)
            .set(DEFERRAL.TARGET_REF, candidate.target.ref)
            .set(DEFERRAL.STATE, DeferralState.DEFERRED.name)
            .set(DEFERRAL.DEFERRED_BY, candidate.deferredBy)
            .set(DEFERRAL.DEFERRED_AT, candidate.deferredAt.atOffset(ZoneOffset.UTC))
            .onConflict(DEFERRAL.TENANT_ID, DEFERRAL.ENTITY_REF, DEFERRAL.TARGET_KIND, DEFERRAL.TARGET_REF)
            .where(DEFERRAL.STATE.eq(DeferralState.DEFERRED.name))
            .doNothing()
            .execute()
        return checkNotNull(findDeferred(candidate.entityRef, candidate.target)) { "no DEFERRED row after defer" }
    }

    override fun undo(
        entityRef: String,
        deferralId: UUID,
    ): Deferral? =
        dsl
            .update(DEFERRAL)
            .set(DEFERRAL.STATE, DeferralState.UNDONE.name)
            .where(DEFERRAL.TENANT_ID.eq(boundTenant))
            .and(DEFERRAL.DEFERRAL_ID.eq(deferralId))
            .and(DEFERRAL.ENTITY_REF.eq(entityRef))
            .and(DEFERRAL.STATE.eq(DeferralState.DEFERRED.name))
            .returning()
            .fetchOne()
            ?.toDeferral()

    override fun findDeferred(
        entityRef: String,
        target: DeferralTarget,
    ): Deferral? =
        dsl
            .selectFrom(DEFERRAL)
            .where(DEFERRAL.TENANT_ID.eq(boundTenant))
            .and(key(entityRef, target))
            .and(DEFERRAL.STATE.eq(DeferralState.DEFERRED.name))
            .fetchOne()
            ?.toDeferral()

    private fun key(
        entityRef: String,
        target: DeferralTarget,
    ): Condition =
        DEFERRAL.ENTITY_REF
            .eq(entityRef)
            .and(DEFERRAL.TARGET_KIND.eq(target.kind.name))
            .and(DEFERRAL.TARGET_REF.eq(target.ref))

    private fun Record.toDeferral() =
        Deferral(
            deferralId = req(DEFERRAL.DEFERRAL_ID),
            entityRef = req(DEFERRAL.ENTITY_REF),
            target = DeferralTarget(DeferralKind.valueOf(req(DEFERRAL.TARGET_KIND)), req(DEFERRAL.TARGET_REF)),
            state = DeferralState.valueOf(req(DEFERRAL.STATE)),
            deferredBy = req(DEFERRAL.DEFERRED_BY),
            deferredAt = req(DEFERRAL.DEFERRED_AT).toInstant(),
        )

    private fun <T : Any> Record.req(field: Field<T?>): T = checkNotNull(get(field)) { "${field.name} is null" }
}
