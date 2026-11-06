package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.ValidationOverride
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.records.ValidationOverrideRecord
import com.dynokore.gst.payload.application.override.NewOverride
import com.dynokore.gst.payload.application.override.OverrideStore
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.domain.override.OverrideStatus
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.UUID

/**
 * The override store over `payload.validation_override`. It opens no transaction: the rows commit with the use case of
 * the caller. The tenant is the one `payload.bound_tenant()` returns; forced row security filters every statement.
 */
class JooqOverrideStore(
    private val dsl: DSLContext,
) : OverrideStore {
    private val table = ValidationOverride.VALIDATION_OVERRIDE

    private val boundTenant: Field<UUID?> = DSL.function(DSL.name("payload", "bound_tenant"), SQLDataType.UUID)

    override fun insert(override: NewOverride): StoredOverride? =
        dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant)
            .set(table.DOC_UUID, override.docUuid)
            .set(table.VERSION, override.version)
            .set(table.CODE, override.code)
            .set(table.REASON, override.reason)
            .set(table.STATUS, override.status.name)
            .set(table.REQUESTED_BY, override.requestedBy)
            .set(table.RELEASED_BY, override.releasedBy)
            .set(table.RELEASED_AT, if (override.releasedBy != null) DSL.currentOffsetDateTime() else null)
            .onConflictDoNothing()
            .returning()
            .fetchOne()
            ?.let(::stored)

    override fun find(
        docUuid: UUID,
        version: Int,
        code: String,
    ): StoredOverride? =
        dsl
            .selectFrom(table)
            .where(
                table.DOC_UUID
                    .eq(docUuid)
                    .and(table.VERSION.eq(version))
                    .and(table.CODE.eq(code)),
            ).fetchOne()
            ?.let(::stored)

    /** One conditional update: of two simultaneous releases one changes the row and the other changes none. */
    override fun release(
        docUuid: UUID,
        version: Int,
        code: String,
        releasedBy: String,
    ): StoredOverride? =
        dsl
            .update(table)
            .set(table.STATUS, OverrideStatus.RELEASED.name)
            .set(table.RELEASED_BY, releasedBy)
            .set(table.RELEASED_AT, DSL.currentOffsetDateTime())
            .where(
                table.DOC_UUID
                    .eq(docUuid)
                    .and(table.VERSION.eq(version))
                    .and(table.CODE.eq(code))
                    .and(table.STATUS.eq(OverrideStatus.PENDING.name)),
            ).returning()
            .fetchOne()
            ?.let(::stored)

    override fun entries(
        docUuid: UUID,
        version: Int,
        after: Pair<Instant, String>?,
        limit: Int,
    ): List<StoredOverride> {
        var condition = table.DOC_UUID.eq(docUuid).and(table.VERSION.eq(version))
        if (after != null) {
            val at = after.first.atOffset(java.time.ZoneOffset.UTC)
            condition = condition.and(DSL.row(table.REQUESTED_AT, table.CODE).gt(DSL.row(at, after.second)))
        }
        return dsl
            .selectFrom(table)
            .where(condition)
            .orderBy(table.REQUESTED_AT, table.CODE)
            .limit(limit)
            .fetch { stored(it) }
    }

    private fun stored(row: ValidationOverrideRecord): StoredOverride =
        StoredOverride(
            checkNotNull(row.docUuid),
            checkNotNull(row.version),
            checkNotNull(row.code),
            checkNotNull(row.reason),
            OverrideStatus.valueOf(checkNotNull(row.status)),
            checkNotNull(row.requestedBy),
            checkNotNull(row.requestedAt).toInstant(),
            row.releasedBy,
            row.releasedAt?.toInstant(),
        )
}
