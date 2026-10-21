package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SOURCE_EVENT
import com.dynokore.gst.canonical.application.source.SourceEventStore
import com.dynokore.gst.canonical.domain.source.SourceKey
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; the tenant condition is explicit
 * and forced row-level security stays behind it.
 */
class JooqSourceEventStore(
    private val dsl: DSLContext,
) : SourceEventStore {
    override fun docUuidOf(key: SourceKey): UUID? =
        dsl
            .select(SOURCE_EVENT.DOC_UUID)
            .from(SOURCE_EVENT)
            .where(SOURCE_EVENT.TENANT_ID.eq(key.tenantId))
            .and(SOURCE_EVENT.SOURCE_SYSTEM.eq(key.sourceSystem))
            .and(SOURCE_EVENT.SOURCE_KEY.eq(key.sourceKey))
            .and(SOURCE_EVENT.SOURCE_REVISION.eq(key.sourceRevision))
            .fetchOne(SOURCE_EVENT.DOC_UUID)

    override fun boundDocUuid(key: SourceKey): UUID? =
        dsl
            .select(SOURCE_EVENT.DOC_UUID)
            .from(SOURCE_EVENT)
            .where(SOURCE_EVENT.TENANT_ID.eq(key.tenantId))
            .and(SOURCE_EVENT.SOURCE_SYSTEM.eq(key.sourceSystem))
            .and(SOURCE_EVENT.SOURCE_KEY.eq(key.sourceKey))
            .orderBy(SOURCE_EVENT.SOURCE_REVISION)
            .limit(1)
            .fetchOne(SOURCE_EVENT.DOC_UUID)

    override fun insert(
        key: SourceKey,
        docUuid: UUID,
    ): Boolean =
        dsl
            .insertInto(SOURCE_EVENT)
            .set(SOURCE_EVENT.TENANT_ID, key.tenantId)
            .set(SOURCE_EVENT.SOURCE_SYSTEM, key.sourceSystem)
            .set(SOURCE_EVENT.SOURCE_KEY, key.sourceKey)
            .set(SOURCE_EVENT.SOURCE_REVISION, key.sourceRevision)
            .set(SOURCE_EVENT.DOC_UUID, docUuid)
            .onConflictDoNothing()
            .execute() == 1
}
