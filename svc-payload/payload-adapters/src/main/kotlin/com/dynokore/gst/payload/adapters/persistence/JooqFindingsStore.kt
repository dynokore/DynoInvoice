package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.ValidationFinding
import com.dynokore.gst.payload.application.finding.FindingsReader
import com.dynokore.gst.payload.application.finding.FindingsRecorder
import com.dynokore.gst.payload.domain.finding.StoredFinding
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * The findings store over the generated `payload.validation_finding` table. It opens no transaction: the findings
 * commit or roll back with the validation of the caller. The tenant is the one `payload.bound_tenant()` returns; row
 * security filters every statement to it.
 */
class JooqFindingsStore(
    private val dsl: DSLContext,
) : FindingsRecorder,
    FindingsReader {
    private val table = ValidationFinding.VALIDATION_FINDING

    private val boundTenant: Field<UUID?> = DSL.function(DSL.name("payload", "bound_tenant"), SQLDataType.UUID)

    override fun replace(
        docUuid: UUID,
        version: Int,
        findings: List<StoredFinding>,
    ) {
        require(version >= 1) { "a document version is 1 or more" }
        lockRecording(docUuid, version)
        dsl
            .deleteFrom(table)
            .where(table.DOC_UUID.eq(docUuid).and(table.VERSION.eq(version)))
            .execute()
        for (finding in findings) {
            dsl
                .insertInto(table)
                .set(table.TENANT_ID, boundTenant)
                .set(table.DOC_UUID, docUuid)
                .set(table.VERSION, version)
                .set(table.CODE, finding.code)
                .set(table.FIELD, finding.field)
                .set(table.MESSAGE, finding.message)
                .set(table.OVERRIDABLE, finding.overridable)
                .execute()
        }
    }

    /**
     * One recording of a (tenant, document, version) at a time: a concurrent recording waits for the commit of the
     * first, then replaces its rows, so the unique key is never violated.
     */
    private fun lockRecording(
        docUuid: UUID,
        version: Int,
    ) {
        val key = DSL.concat(boundTenant.cast(SQLDataType.VARCHAR), DSL.inline("|$docUuid|$version"))
        dsl
            .select(
                DSL.function(
                    DSL.name("pg_advisory_xact_lock"),
                    SQLDataType.OTHER,
                    DSL.function(DSL.name("hashtextextended"), SQLDataType.BIGINT, key, DSL.inline(0L)),
                ),
            ).fetch()
    }

    override fun blocking(
        docUuid: UUID,
        version: Int,
    ): List<StoredFinding> =
        dsl
            .selectFrom(table)
            .where(table.DOC_UUID.eq(docUuid).and(table.VERSION.eq(version)))
            .orderBy(table.ID)
            .fetch {
                StoredFinding(
                    checkNotNull(it.code),
                    checkNotNull(it.field),
                    checkNotNull(it.message),
                    checkNotNull(it.overridable),
                )
            }
}
