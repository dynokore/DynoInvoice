package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.WindowDocument
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.LocalDate
import java.util.UUID

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; the tenant predicate is the
 * framework's and forced row-level security stays behind it. `document` has no `version` column (its pointer is
 * `current_version` and its key spans the partition columns), so the conditional pointer move is written here and
 * reports the outcome type of the AggregateStore.
 */
class JooqDocumentStore(
    private val dsl: DSLContext,
) : DocumentStore {
    override fun insert(document: NewDocument) {
        dsl
            .insertInto(DOCUMENT)
            .set(DOCUMENT.DOC_UUID, document.docUuid)
            .set(DOCUMENT.TENANT_ID, document.tenantId)
            .set(DOCUMENT.ENTITY_ID, document.entityId)
            .set(DOCUMENT.BUSINESS_UNIT_ID, document.businessUnitId)
            .set(DOCUMENT.DIRECTION, document.direction.name)
            .set(DOCUMENT.DOCUMENT_TYPE_CODE, document.documentTypeCode)
            .set(DOCUMENT.DOCUMENT_NUMBER, document.documentNumber)
            .set(DOCUMENT.ISSUER_PARTY_KEY, document.issuerPartyKey)
            .set(DOCUMENT.FISCAL_YEAR, document.fiscalYear)
            .set(DOCUMENT.INTERNAL_REFERENCE, document.internalReference)
            .set(DOCUMENT.PARTITION_DATE, document.first.transactionDate)
            .set(DOCUMENT.TRANSACTION_DATE, document.first.transactionDate)
            .set(DOCUMENT.PRESCRIBED_PERIOD_ID, document.prescribedPeriodId)
            .set(DOCUMENT.CURRENT_VERSION, FIRST_VERSION)
            .set(DOCUMENT.STATE, document.state.name)
            .execute()
        val key = VersionKey(document.docUuid, FIRST_VERSION, document.tenantId, document.entityId)
        insertVersion(key, document.first.transactionDate, document.first)
    }

    override fun createVersion(
        docUuid: UUID,
        expectedVersion: Int,
        version: NewVersion,
    ): UpdateOutcome {
        val next = expectedVersion + 1
        val moved =
            dsl
                .update(DOCUMENT)
                .set(DOCUMENT.CURRENT_VERSION, next)
                .set(DOCUMENT.TRANSACTION_DATE, version.transactionDate)
                .where(DOCUMENT.DOC_UUID.eq(docUuid))
                .and(DOCUMENT.CURRENT_VERSION.eq(expectedVersion))
                .returning(DOCUMENT.TENANT_ID, DOCUMENT.ENTITY_ID, DOCUMENT.PARTITION_DATE)
                .fetchOne() ?: return UpdateOutcome.Conflict
        val key = VersionKey(docUuid, next, checkNotNull(moved.tenantId), checkNotNull(moved.entityId))
        insertVersion(key, checkNotNull(moved.partitionDate), version)
        return UpdateOutcome.Updated(next.toLong())
    }

    private data class VersionKey(
        val docUuid: UUID,
        val number: Int,
        val tenantId: UUID,
        val entityId: UUID,
    )

    private fun insertVersion(
        key: VersionKey,
        partitionDate: LocalDate,
        version: NewVersion,
    ) {
        dsl
            .insertInto(DOCUMENT_VERSION)
            .set(DOCUMENT_VERSION.DOC_UUID, key.docUuid)
            .set(DOCUMENT_VERSION.VERSION, key.number)
            .set(DOCUMENT_VERSION.TENANT_ID, key.tenantId)
            .set(DOCUMENT_VERSION.ENTITY_ID, key.entityId)
            .set(DOCUMENT_VERSION.PARTITION_DATE, partitionDate)
            .set(DOCUMENT_VERSION.CANONICAL, DSL.`val`(version.canonical).cast(SQLDataType.JSONB))
            .set(DOCUMENT_VERSION.SCOPE_VERDICT, version.scopeVerdict.name)
            .set(DOCUMENT_VERSION.SCOPE_PACK_VERSION, version.scopePackVersion)
            .set(DOCUMENT_VERSION.MAPPING_PACK_VERSION, version.mappingPackVersion)
            .set(DOCUMENT_VERSION.MDE_PACK_VERSION, version.mdePackVersion)
            .set(DOCUMENT_VERSION.SPECIFICATION_VERSION, version.specificationVersion.name)
            .set(DOCUMENT_VERSION.CREATED_BY, version.createdBy)
            .execute()
    }

    @Suppress("LongParameterList") // the signature of the window read of INV-REQ-772 b1
    override fun readWindow(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
        pageSize: Int,
        afterDocUuid: UUID?,
    ): List<WindowDocument> =
        dsl
            .select(
                DOCUMENT.DOC_UUID,
                DOCUMENT.DOCUMENT_TYPE_CODE,
                DOCUMENT.TRANSACTION_DATE,
                DOCUMENT.CURRENT_VERSION,
                DOCUMENT_VERSION.CANONICAL,
            ).from(DOCUMENT)
            .join(DOCUMENT_VERSION)
            .on(DOCUMENT_VERSION.DOC_UUID.eq(DOCUMENT.DOC_UUID))
            .and(DOCUMENT_VERSION.VERSION.eq(DOCUMENT.CURRENT_VERSION))
            .and(DOCUMENT_VERSION.PARTITION_DATE.eq(DOCUMENT.PARTITION_DATE))
            .and(DOCUMENT_VERSION.ENTITY_ID.eq(DOCUMENT.ENTITY_ID))
            .where(DOCUMENT.TENANT_ID.eq(tenantId))
            .and(DOCUMENT.ENTITY_ID.eq(entityId))
            .and(DOCUMENT.TRANSACTION_DATE.between(from, to))
            .and(if (afterDocUuid == null) DSL.noCondition() else DOCUMENT.DOC_UUID.gt(afterDocUuid))
            .orderBy(DOCUMENT.DOC_UUID)
            .limit(pageSize)
            .fetch { row ->
                WindowDocument(
                    checkNotNull(row.get(DOCUMENT.DOC_UUID)),
                    checkNotNull(row.get(DOCUMENT.DOCUMENT_TYPE_CODE)),
                    checkNotNull(row.get(DOCUMENT.TRANSACTION_DATE)),
                    checkNotNull(row.get(DOCUMENT.CURRENT_VERSION)),
                    checkNotNull(row.get(DOCUMENT_VERSION.CANONICAL)).data(),
                )
            }

    private companion object {
        const val FIRST_VERSION = 1
    }
}
