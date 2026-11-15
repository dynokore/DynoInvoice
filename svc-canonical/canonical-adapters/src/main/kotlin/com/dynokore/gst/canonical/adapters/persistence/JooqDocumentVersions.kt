package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.application.document.DocumentVersions
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; forced row-level security binds
 * the read to the tenant of the session, so a document of another tenant is invisible and reads like a new Doc UUID.
 * The pointer `current_version` is the one source of the version sequence: the next number is that value plus 1, or 1
 * when no row is visible. The call writes nothing; a race is decided by the conditional move of `createVersion`
 * (optimistic concurrency, INV-REQ-2574).
 */
class JooqDocumentVersions(
    private val dsl: DSLContext,
) : DocumentVersions {
    override fun nextVersion(docUuid: UUID): Int {
        val current =
            dsl
                .select(DOCUMENT.CURRENT_VERSION)
                .from(DOCUMENT)
                .where(DOCUMENT.DOC_UUID.eq(docUuid))
                .fetchOne(DOCUMENT.CURRENT_VERSION)
        return (current ?: 0) + 1
    }
}
