package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.application.lookup.DecisionTraceReader
import com.dynokore.gst.evidence.application.lookup.StoredDecisionTrace
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The latest `decision_trace` row of (tenant, document, version) over the tenant-bound [DSLContext] (row-level
 * security plus an explicit tenant predicate). A row with a null version never matches `version = ?` (INV-Q-20484 A).
 */
class JooqDecisionTraceReader(
    private val dsl: DSLContext,
) : DecisionTraceReader {
    override fun latest(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): StoredDecisionTrace? {
        val row =
            dsl
                .select(RECORDED_AT, CONTENT)
                .from(TABLE)
                .where(TENANT_ID.eq(tenant.value))
                .and(DOC_UUID.eq(docUuid))
                .and(VERSION.eq(version))
                .orderBy(SEQ.desc())
                .limit(1)
                .fetchOne()
        return row?.let {
            DecisionTraceContent.parse(
                checkNotNull(it[RECORDED_AT]).toInstant(),
                it[CONTENT] ?: ByteArray(0),
            )
        }
    }

    private companion object {
        val TABLE = DSL.table(DSL.name("evidence", "decision_trace"))
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), UUID::class.java)
        val DOC_UUID = DSL.field(DSL.name("doc_uuid"), UUID::class.java)
        val VERSION = DSL.field(DSL.name("version"), Int::class.javaObjectType)
        val SEQ = DSL.field(DSL.name("seq"), Long::class.javaObjectType)
        val RECORDED_AT = DSL.field(DSL.name("recorded_at"), OffsetDateTime::class.java)
        val CONTENT = DSL.field(DSL.name("content"), ByteArray::class.java)
    }
}
