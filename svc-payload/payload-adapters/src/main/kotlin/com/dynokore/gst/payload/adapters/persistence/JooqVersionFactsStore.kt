package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.DocumentVersionFact
import com.dynokore.gst.payload.application.version.VersionFactsReader
import com.dynokore.gst.payload.application.version.VersionFactsRecorder
import com.dynokore.gst.payload.domain.version.VersionFacts
import org.jooq.DSLContext
import java.util.UUID

/**
 * The version facts over the generated `payload.document_version_fact` table (INV-REQ-1293). It opens no transaction:
 * the insert commits or rolls back with the caller. The tenant is the one bound to the request ([boundTenant]); the
 * tenant predicate guard of the wired [DSLContext] requires it as a literal, and row security
 * (`payload.bound_tenant()`) filters every statement to the tenant of the database session as a second line.
 */
class JooqVersionFactsStore(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : VersionFactsRecorder,
    VersionFactsReader {
    private val table = DocumentVersionFact.DOCUMENT_VERSION_FACT

    override fun record(
        docUuid: UUID,
        version: Int,
        facts: VersionFacts,
    ): Boolean =
        dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.DOC_UUID, docUuid)
            .set(table.VERSION, version)
            .set(table.ENTITY_REF, facts.entityRef)
            .set(table.TOTAL_SGD, facts.totalSgd)
            .onConflictDoNothing()
            .execute() == 1

    override fun find(
        docUuid: UUID,
        version: Int,
    ): VersionFacts? =
        dsl
            .selectFrom(table)
            .where(
                table.TENANT_ID
                    .eq(boundTenant())
                    .and(table.DOC_UUID.eq(docUuid))
                    .and(table.VERSION.eq(version)),
            ).fetchOne()
            ?.takeIf { it.totalSgd != null } // the reader answers nothing for a stored row whose total is absent
            ?.let { VersionFacts(checkNotNull(it.entityRef), it.totalSgd) }
}
