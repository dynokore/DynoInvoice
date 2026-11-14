package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.DEFERRAL
import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.EXCEPTION
import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.PERIOD_DUE_DATE
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionQueue
import com.dynokore.gst.consoleBff.application.exceptions.QueuedException
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Select
import org.jooq.impl.DSL
import java.time.ZoneOffset
import java.util.UUID

/**
 * The exception queue read over `console_bff.exception` with the due date of `console_bff.period_due_date` joined at
 * read time by (tenant, entity, period) and the deferral marker of `console_bff.deferral` matched by whole-key
 * equality (keyset pagination with a seek condition, never an offset). Isolation is the forced row level security of
 * the tables under the caller's signed tenant binding; the statement also names `e.tenant_id` (defence in depth,
 * NIST SP 800-53 AC-3). It reads stored rows only and writes nothing.
 */
class JooqExceptionQueue(
    private val dsl: DSLContext,
) : ExceptionQueue {
    private val e = EXCEPTION.`as`("e")
    private val d = PERIOD_DUE_DATE.`as`("d")
    private val f = DEFERRAL.`as`("f")

    @Suppress("LongParameterList") // port shape fixed by INV-T-21104
    override fun page(
        tenantId: String,
        entityRefs: Set<String>,
        state: ItemState?,
        deferred: Boolean,
        after: ExceptionSortKey?,
        limit: Int,
    ): List<QueuedException> {
        if (entityRefs.isEmpty()) return emptyList()
        return statement(UUID.fromString(tenantId), entityRefs, state, deferred, after, limit)
            .fetch()
            .map { it.toQueued() }
    }

    @Suppress("LongParameterList", "SpreadOperator") // one argument per filter; jOOQ's any() takes a vararg
    internal fun statement(
        tenant: UUID,
        entityRefs: Set<String>,
        state: ItemState?,
        deferred: Boolean,
        after: ExceptionSortKey?,
        limit: Int,
    ): Select<out Record> {
        val covered = DSL.selectOne().from(f).where(deferralMatch())
        return dsl
            .select(e.fields().toList())
            .select(d.DUE_DATE)
            .from(e)
            .leftJoin(d)
            .on(d.TENANT_ID.eq(e.TENANT_ID))
            .and(d.ENTITY_REF.eq(e.ENTITY_REF))
            .and(d.PERIOD_REF.eq(e.PERIOD_REF))
            .and(e.PERIOD_REF.ne(""))
            .where(e.TENANT_ID.eq(tenant))
            .and(e.ENTITY_REF.eq(DSL.any(*entityRefs.toTypedArray())))
            .and(if (state == null) DSL.noCondition() else e.STATE.eq(state.name))
            .and(if (deferred) DSL.exists(covered) else DSL.notExists(covered))
            .and(if (after == null) DSL.noCondition() else seek(after))
            .orderBy(d.DUE_DATE.asc().nullsLast(), e.RAISED_AT.asc(), e.EXCEPTION_ID.asc())
            .limit(limit)
    }

    /** A deferral of the same tenant and entity, still DEFERRED, whose whole target key equals the row's. */
    private fun deferralMatch(): Condition =
        f.TENANT_ID
            .eq(e.TENANT_ID)
            .and(f.ENTITY_REF.eq(e.ENTITY_REF))
            .and(f.STATE.eq("DEFERRED"))
            .and(
                f.TARGET_KIND
                    .eq("DOCUMENT")
                    .and(f.TARGET_REF.eq(e.DOC_UUID))
                    .or(
                        f.TARGET_KIND
                            .eq("GROUP")
                            .and(f.TARGET_REF.eq(DSL.concat(e.REMEDIATION_OWNER, DSL.inline("/"), e.CODE))),
                    ),
            )

    private fun seek(after: ExceptionSortKey): Condition {
        val rest =
            DSL
                .row(e.RAISED_AT, e.EXCEPTION_ID)
                .gt(DSL.row(after.raisedAt.atOffset(ZoneOffset.UTC), UUID.fromString(after.exceptionId)))
        val due = after.dueDate
        return if (due == null) {
            d.DUE_DATE.isNull.and(rest)
        } else {
            d.DUE_DATE
                .gt(due)
                .or(d.DUE_DATE.isNull)
                .or(d.DUE_DATE.eq(due).and(rest))
        }
    }

    private fun Record.toQueued() =
        QueuedException(
            ExceptionRow(
                tenantId = req(e.TENANT_ID).toString(),
                exceptionId = req(e.EXCEPTION_ID),
                entityRef = req(e.ENTITY_REF),
                docUuid = req(e.DOC_UUID),
                code = req(e.CODE),
                remediationOwner = req(e.REMEDIATION_OWNER),
                disposition = req(e.DISPOSITION),
                state = ItemState.valueOf(req(e.STATE)),
                raisedAt = req(e.RAISED_AT).toInstant(),
                periodRef = req(e.PERIOD_REF),
                docsequence = req(e.DOCSEQUENCE),
            ),
            get(d.DUE_DATE),
        )

    private fun <T : Any> Record.req(field: org.jooq.Field<T?>): T =
        checkNotNull(get(field)) { "${field.name} is null" }
}
