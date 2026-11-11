package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Acknowledgement.Companion.ACKNOWLEDGEMENT
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.ArchiveVolumePublished.Companion.ARCHIVE_VOLUME_PUBLISHED
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AuditLog.Companion.AUDIT_LOG
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DecisionTrace.Companion.DECISION_TRACE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DocumentLocator.Companion.DOCUMENT_LOCATOR
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidencePayload.Companion.EVIDENCE_PAYLOAD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PendingRecord.Companion.PENDING_RECORD
import com.dynokore.gst.evidence.application.archive.ArchiveVolumeLedger
import com.dynokore.gst.evidence.application.archive.StoredContentBytes
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.UUID

/**
 * The bytes of retained content one entity holds in the database (INV-Q-12681 A, INV-Q-12683 A): the sum of
 * `octet_length` over exactly the covered content columns `evidence_payload.content`, `acknowledgement.content`,
 * `decision_trace.content`, `audit_log.content` and `pending_record.event_json` (a pending record belongs to the
 * entity of the document locator of its tenant, document and version). jOOQ only, over the service's tenant-bound
 * [DSLContext]; row-level security limits every read to the bound tenant, and each query also names the tenant.
 */
class JooqStoredContentBytes(
    private val dsl: DSLContext,
) : StoredContentBytes {
    /** One covered content column of a table that carries the entity itself. */
    private class Column(
        val table: Table<*>,
        val content: Field<*>,
        val tenant: Field<UUID?>,
        val entity: Field<String?>,
    )

    private val columns =
        listOf(
            Column(EVIDENCE_PAYLOAD, EVIDENCE_PAYLOAD.CONTENT, EVIDENCE_PAYLOAD.TENANT_ID, EVIDENCE_PAYLOAD.ENTITY_REF),
            Column(ACKNOWLEDGEMENT, ACKNOWLEDGEMENT.CONTENT, ACKNOWLEDGEMENT.TENANT_ID, ACKNOWLEDGEMENT.ENTITY_REF),
            Column(DECISION_TRACE, DECISION_TRACE.CONTENT, DECISION_TRACE.TENANT_ID, DECISION_TRACE.ENTITY_REF),
            Column(AUDIT_LOG, AUDIT_LOG.CONTENT, AUDIT_LOG.TENANT_ID, AUDIT_LOG.ENTITY_REF),
        )

    override fun bytes(
        tenant: TenantId,
        entityRef: String,
    ): Long {
        val total = columns.sumOf { sum(it, tenant, entityRef) } + pending(tenant, entityRef)
        check(total >= 0) { "the stored content bytes are negative" }
        return total
    }

    private fun sum(
        column: Column,
        tenant: TenantId,
        entityRef: String,
    ): Long =
        dsl
            .select(octetSum(column.content))
            .from(column.table)
            .where(column.tenant.eq(tenant.value), column.entity.eq(entityRef))
            .fetchOne(0, Long::class.java) ?: 0L

    private fun pending(
        tenant: TenantId,
        entityRef: String,
    ): Long =
        dsl
            .select(octetSum(PENDING_RECORD.EVENT_JSON))
            .from(PENDING_RECORD)
            .join(DOCUMENT_LOCATOR)
            .on(
                DOCUMENT_LOCATOR.TENANT_ID.eq(PENDING_RECORD.TENANT_ID),
                DOCUMENT_LOCATOR.DOC_UUID.eq(PENDING_RECORD.DOC_UUID),
                DOCUMENT_LOCATOR.VERSION.eq(PENDING_RECORD.VERSION),
            ).where(
                PENDING_RECORD.TENANT_ID.eq(tenant.value),
                DOCUMENT_LOCATOR.ENTITY_REF.eq(entityRef),
            ).fetchOne(0, Long::class.java) ?: 0L

    private fun octetSum(column: Field<*>): Field<Long> =
        DSL.coalesce(
            DSL.sum(DSL.function(DSL.name("octet_length"), SQLDataType.INTEGER, column)).cast(SQLDataType.BIGINT),
            0L,
        )
}

/** `archive_volume_published` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqArchiveVolumeLedger(
    private val dsl: DSLContext,
) : ArchiveVolumeLedger {
    override fun last(
        tenant: TenantId,
        entityRef: String,
    ): Long? =
        dsl
            .select(ARCHIVE_VOLUME_PUBLISHED.BYTES)
            .from(ARCHIVE_VOLUME_PUBLISHED)
            .where(
                ARCHIVE_VOLUME_PUBLISHED.TENANT_ID.eq(tenant.value),
                ARCHIVE_VOLUME_PUBLISHED.ENTITY_REF.eq(entityRef),
            ).fetchOne(0, Long::class.java)

    override fun record(
        tenant: TenantId,
        entityRef: String,
        bytes: Long,
        measuredAt: Instant,
    ) {
        dsl
            .insertInto(ARCHIVE_VOLUME_PUBLISHED)
            .set(ARCHIVE_VOLUME_PUBLISHED.TENANT_ID, tenant.value)
            .set(ARCHIVE_VOLUME_PUBLISHED.ENTITY_REF, entityRef)
            .set(ARCHIVE_VOLUME_PUBLISHED.BYTES, bytes)
            .set(ARCHIVE_VOLUME_PUBLISHED.MEASURED_AT, measuredAt.utc())
            .onConflict(ARCHIVE_VOLUME_PUBLISHED.TENANT_ID, ARCHIVE_VOLUME_PUBLISHED.ENTITY_REF)
            .doUpdate()
            .set(ARCHIVE_VOLUME_PUBLISHED.BYTES, bytes)
            .set(ARCHIVE_VOLUME_PUBLISHED.MEASURED_AT, measuredAt.utc())
            .execute()
    }
}
