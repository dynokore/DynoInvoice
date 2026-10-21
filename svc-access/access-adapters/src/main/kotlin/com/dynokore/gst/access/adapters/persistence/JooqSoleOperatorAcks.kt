package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.SOLE_OPERATOR_ACK
import com.dynokore.gst.access.application.separation.SoleOperatorAck
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The listeners of the DSLContext add the bound tenant to every statement; row-level security stays behind. */
class JooqSoleOperatorAcks(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : SoleOperatorAcks {
    override fun findActive(): SoleOperatorAck? =
        dsl
            .selectFrom(SOLE_OPERATOR_ACK)
            .where(SOLE_OPERATOR_ACK.STATUS.eq(ACTIVE))
            .fetchOne()
            ?.let { toAck(it) }

    override fun find(ref: String): SoleOperatorAck? =
        dsl
            .selectFrom(SOLE_OPERATOR_ACK)
            .where(SOLE_OPERATOR_ACK.ACK_REF.eq(ref))
            .fetchOne()
            ?.let { toAck(it) }

    override fun insert(compensatingControls: String): SoleOperatorAck {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val ref =
            "SOA-" +
                ids
                    .next(clock.instant())
                    .toString()
                    .replace("-", "")
                    .takeLast(REF_DIGITS)
        dsl
            .insertInto(SOLE_OPERATOR_ACK)
            .set(SOLE_OPERATOR_ACK.ID, ids.next(clock.instant()))
            .set(SOLE_OPERATOR_ACK.ACK_REF, ref)
            .set(SOLE_OPERATOR_ACK.COMPENSATING_CONTROLS, compensatingControls)
            .set(SOLE_OPERATOR_ACK.STATUS, ACTIVE)
            .set(SOLE_OPERATOR_ACK.CREATED_AT, now)
            .set(SOLE_OPERATOR_ACK.CREATED_BY, actor)
            .set(SOLE_OPERATOR_ACK.UPDATED_AT, now)
            .set(SOLE_OPERATOR_ACK.UPDATED_BY, actor)
            .set(SOLE_OPERATOR_ACK.VERSION, 0L)
            .execute()
        return checkNotNull(find(ref)) { "the acknowledgement just written is not readable" }
    }

    override fun withdraw(ref: String) {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        dsl
            .update(SOLE_OPERATOR_ACK)
            .set(SOLE_OPERATOR_ACK.STATUS, WITHDRAWN)
            .set(SOLE_OPERATOR_ACK.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(SOLE_OPERATOR_ACK.UPDATED_BY, actor)
            .set(SOLE_OPERATOR_ACK.VERSION, SOLE_OPERATOR_ACK.VERSION.plus(1))
            .where(SOLE_OPERATOR_ACK.ACK_REF.eq(ref))
            .execute()
    }

    private fun toAck(row: Record): SoleOperatorAck =
        SoleOperatorAck(
            ref = checkNotNull(row.get(SOLE_OPERATOR_ACK.ACK_REF)) { "ack_ref is not null" },
            tenantRef = tenants.current().toString(),
            compensatingControls =
                checkNotNull(row.get(SOLE_OPERATOR_ACK.COMPENSATING_CONTROLS)) { "compensating_controls is not null" },
            active = row.get(SOLE_OPERATOR_ACK.STATUS) == ACTIVE,
        )

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val WITHDRAWN = "WITHDRAWN"
        const val REF_DIGITS = 8
    }
}
