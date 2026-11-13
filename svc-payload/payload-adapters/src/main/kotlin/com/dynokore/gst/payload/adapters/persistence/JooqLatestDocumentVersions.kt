package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.DocumentVersionFact
import com.dynokore.gst.payload.application.version.LatestDocumentVersions
import com.dynokore.gst.payload.application.version.LatestVersionFacts
import org.jooq.DSLContext
import java.util.UUID

/**
 * The latest stored version of a document over `payload.document_version_fact` (INV-REQ-500). It opens no transaction.
 * The tenant is the one bound to the request ([boundTenant]); row security (`payload.bound_tenant()`) is the second
 * line. Unlike `VersionFactsReader.find`, a stored version without a total is still the latest version.
 */
class JooqLatestDocumentVersions(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : LatestDocumentVersions {
    private val table = DocumentVersionFact.DOCUMENT_VERSION_FACT

    override fun latest(docUuid: UUID): LatestVersionFacts? =
        dsl
            .select(table.VERSION, table.ENTITY_REF)
            .from(table)
            .where(table.TENANT_ID.eq(boundTenant()).and(table.DOC_UUID.eq(docUuid)))
            .orderBy(table.VERSION.desc())
            .limit(1)
            .fetchOne()
            ?.let { LatestVersionFacts(checkNotNull(it[table.VERSION]), checkNotNull(it[table.ENTITY_REF])) }
}
