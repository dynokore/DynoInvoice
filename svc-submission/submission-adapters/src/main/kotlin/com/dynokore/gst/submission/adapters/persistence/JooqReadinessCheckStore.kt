package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessCheck
import com.dynokore.gst.submission.application.readiness.MAX_READINESS_PAGE
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.ReadinessCheckRecorder
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.ZoneOffset

/**
 * [ReadinessCheckRecorder] and [ReadinessCheckReader] over `submission.readiness_check` (INV-Q-17042 A). The tenant is
 * the one bound to the transaction; [append] is one insert in the caller's open transaction and opens none.
 */
class JooqReadinessCheckStore(
    private val dsl: DSLContext,
) : ReadinessCheckRecorder,
    ReadinessCheckReader {
    private val t = ReadinessCheck.READINESS_CHECK

    @Suppress("LongParameterList")
    override fun append(
        entityRef: String,
        periodRef: String,
        verdict: CheckVerdict,
        checkedAt: Instant,
        approver: String,
        outcomeValues: String?,
    ): StoredReadinessCheck {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(approver.isNotBlank()) { "approver must not be blank" }
        val row =
            dsl
                .insertInto(t)
                .set(t.ENTITY_REF, entityRef)
                .set(t.PERIOD_REF, periodRef)
                .set(t.VERDICT, verdict.name)
                .set(t.CHECKED_AT, checkedAt.atOffset(ZoneOffset.UTC))
                .set(t.APPROVER, approver)
                .set(t.OUTCOME_VALUES, DSL.`val`(outcomeValues, SQLDataType.VARCHAR).cast(SQLDataType.JSONB))
                .returning(t.SEQ)
                .fetchOne()
        return StoredReadinessCheck(
            seq = checkNotNull(checkNotNull(row).seq),
            checkId = StoredReadinessCheck.checkIdOf(checkNotNull(row.seq)),
            entityRef = entityRef,
            periodRef = periodRef,
            verdict = verdict,
            checkedAt = checkedAt,
            approver = approver,
            outcomeValues = outcomeValues,
        )
    }

    override fun latest(
        entityRef: String,
        periodRef: String,
    ): StoredReadinessCheck? = page(entityRef, periodRef, 1, null).firstOrNull()

    override fun page(
        entityRef: String,
        periodRef: String,
        limit: Int,
        before: Long?,
    ): List<StoredReadinessCheck> {
        require(limit in 1..MAX_READINESS_PAGE) { "limit must be between 1 and $MAX_READINESS_PAGE" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        return dsl
            .selectFrom(t)
            .where(t.ENTITY_REF.eq(entityRef))
            .and(t.PERIOD_REF.eq(periodRef))
            .let { q -> if (before != null) q.and(t.SEQ.lt(before)) else q }
            .orderBy(t.SEQ.desc())
            .limit(limit)
            .fetch()
            .map { toStored(it) }
    }

    private fun toStored(r: Record): StoredReadinessCheck {
        val seq = checkNotNull(r.get(t.SEQ))
        val stored = checkNotNull(r.get(t.VERDICT))
        val verdict =
            CheckVerdict.entries.firstOrNull { it.name == stored }
                ?: error("stored readiness verdict is not READY or NOT_READY")
        return StoredReadinessCheck(
            seq = seq,
            checkId = StoredReadinessCheck.checkIdOf(seq),
            entityRef = checkNotNull(r.get(t.ENTITY_REF)),
            periodRef = checkNotNull(r.get(t.PERIOD_REF)),
            verdict = verdict,
            checkedAt = checkNotNull(r.get(t.CHECKED_AT)).toInstant(),
            approver = checkNotNull(r.get(t.APPROVER)),
            outcomeValues = r.get(t.OUTCOME_VALUES)?.data(),
        )
    }
}
