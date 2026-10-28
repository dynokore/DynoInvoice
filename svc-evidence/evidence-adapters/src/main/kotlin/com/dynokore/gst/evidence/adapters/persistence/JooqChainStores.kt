package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DocumentLocator.Companion.DOCUMENT_LOCATOR
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PendingRecord.Companion.PENDING_RECORD
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.chain.AppliedStore
import com.dynokore.gst.evidence.application.chain.LocatorStore
import com.dynokore.gst.evidence.application.chain.PendingRecord
import com.dynokore.gst.evidence.application.chain.PendingStore
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal fun Instant.utc() = atOffset(ZoneOffset.UTC)

/** `document_locator` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqLocatorStore(
    private val dsl: DSLContext,
) : LocatorStore {
    override fun find(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): DocumentLocator? =
        dsl
            .selectFrom(DOCUMENT_LOCATOR)
            .where(
                DOCUMENT_LOCATOR.TENANT_ID.eq(tenant.value),
                DOCUMENT_LOCATOR.DOC_UUID.eq(docUuid),
                DOCUMENT_LOCATOR.VERSION.eq(version),
            ).fetchOne()
            ?.let {
                DocumentLocator(
                    checkNotNull(it.docUuid),
                    checkNotNull(it.version),
                    checkNotNull(it.entityRef),
                    checkNotNull(it.periodRef),
                    checkNotNull(it.transactionDate),
                    checkNotNull(it.traceReference),
                    it.documentNumber,
                )
            }

    override fun insert(
        tenant: TenantId,
        eventId: String,
        locator: DocumentLocator,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(DOCUMENT_LOCATOR)
            .set(DOCUMENT_LOCATOR.TENANT_ID, tenant.value)
            .set(DOCUMENT_LOCATOR.DOC_UUID, locator.docUuid)
            .set(DOCUMENT_LOCATOR.VERSION, locator.version)
            .set(DOCUMENT_LOCATOR.ENTITY_REF, locator.entityRef)
            .set(DOCUMENT_LOCATOR.PERIOD_REF, locator.periodRef)
            .set(DOCUMENT_LOCATOR.TRANSACTION_DATE, locator.transactionDate)
            .set(DOCUMENT_LOCATOR.TRACE_REFERENCE, locator.traceReference)
            .set(DOCUMENT_LOCATOR.DOCUMENT_NUMBER, locator.documentNumber)
            .set(DOCUMENT_LOCATOR.SOURCE_EVENT_ID, eventId)
            .set(DOCUMENT_LOCATOR.RECORDED_AT, recordedAt.utc())
            .onConflictDoNothing()
            .execute() == 1

    override fun lock(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ) {
        val key = DSL.`val`("${tenant.value}:$docUuid:$version")
        val hash = DSL.function(DSL.name("hashtextextended"), SQLDataType.BIGINT, key, DSL.`val`(0L))
        dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, hash)).fetch()
    }
}

/** `pending_record`. */
class JooqPendingStore(
    private val dsl: DSLContext,
) : PendingStore {
    override fun insert(
        tenant: TenantId,
        record: PendingRecord,
    ): Boolean =
        dsl
            .insertInto(PENDING_RECORD)
            .set(PENDING_RECORD.TENANT_ID, tenant.value)
            .set(PENDING_RECORD.SOURCE_EVENT_ID, record.eventId)
            .set(PENDING_RECORD.KIND, record.kind.table)
            .set(PENDING_RECORD.DOC_UUID, record.docUuid)
            .set(PENDING_RECORD.VERSION, record.version)
            .set(PENDING_RECORD.EVENT_JSON, record.eventJson)
            .set(PENDING_RECORD.ARRIVED_AT, record.arrivedAt.utc())
            .onConflictDoNothing()
            .execute() == 1

    override fun unapplied(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): List<PendingRecord> =
        dsl
            .select(
                PENDING_RECORD.SOURCE_EVENT_ID,
                PENDING_RECORD.KIND,
                PENDING_RECORD.DOC_UUID,
                PENDING_RECORD.VERSION,
                PENDING_RECORD.EVENT_JSON,
                PENDING_RECORD.ARRIVED_AT,
            ).from(PENDING_RECORD)
            .leftAntiJoin(APPLIED_RECORD)
            .on(
                APPLIED_RECORD.TENANT_ID
                    .eq(PENDING_RECORD.TENANT_ID)
                    .and(APPLIED_RECORD.SOURCE_EVENT_ID.eq(PENDING_RECORD.SOURCE_EVENT_ID)),
            ).where(
                PENDING_RECORD.TENANT_ID.eq(tenant.value),
                PENDING_RECORD.DOC_UUID.eq(docUuid),
                PENDING_RECORD.VERSION.eq(version),
            ).orderBy(PENDING_RECORD.ARRIVED_AT, PENDING_RECORD.SOURCE_EVENT_ID)
            .fetch()
            .map {
                PendingRecord(
                    checkNotNull(it.get(PENDING_RECORD.SOURCE_EVENT_ID)),
                    kindOf(checkNotNull(it.get(PENDING_RECORD.KIND))),
                    checkNotNull(it.get(PENDING_RECORD.DOC_UUID)),
                    checkNotNull(it.get(PENDING_RECORD.VERSION)),
                    checkNotNull(it.get(PENDING_RECORD.EVENT_JSON)),
                    checkNotNull(it.get(PENDING_RECORD.ARRIVED_AT)).toInstant(),
                )
            }
}

/** `applied_record`. */
class JooqAppliedStore(
    private val dsl: DSLContext,
) : AppliedStore {
    override fun isApplied(
        tenant: TenantId,
        eventId: String,
    ): Boolean =
        dsl.fetchExists(
            APPLIED_RECORD,
            APPLIED_RECORD.TENANT_ID.eq(tenant.value).and(APPLIED_RECORD.SOURCE_EVENT_ID.eq(eventId)),
        )

    override fun insert(
        tenant: TenantId,
        record: AppliedRecord,
    ) {
        dsl
            .insertInto(APPLIED_RECORD)
            .set(APPLIED_RECORD.TENANT_ID, tenant.value)
            .set(APPLIED_RECORD.SOURCE_EVENT_ID, record.eventId)
            .set(APPLIED_RECORD.KIND, record.kind.table)
            .set(APPLIED_RECORD.ENTITY_REF, record.chain.entityRef)
            .set(APPLIED_RECORD.PERIOD_REF, record.chain.periodRef)
            .set(APPLIED_RECORD.SEQ, record.sequence)
            .set(APPLIED_RECORD.APPLIED_AT, record.appliedAt.utc())
            .execute()
    }
}

private fun kindOf(table: String): RecordKind = RecordKind.entries.single { it.table == table }
