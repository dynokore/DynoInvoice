package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.application.document.PrecedingDocumentLookup
import com.dynokore.gst.canonical.application.document.PrecedingReference
import com.dynokore.gst.canonical.domain.document.PrecedingCandidate
import org.jooq.DSLContext

/**
 * TPL-11. jOOQ only. The tenant predicate is the framework's and row-level security stays behind it; the type code and
 * the fiscal year are deliberately not filtered, so an original of an earlier year is a candidate.
 */
class JooqPrecedingDocumentLookup(
    private val dsl: DSLContext,
) : PrecedingDocumentLookup {
    override fun candidates(reference: PrecedingReference): List<PrecedingCandidate> =
        dsl
            .select(DOCUMENT.DOC_UUID, DOCUMENT.TRANSACTION_DATE)
            .from(DOCUMENT)
            .where(DOCUMENT.ENTITY_ID.eq(reference.entityId))
            .and(DOCUMENT.DIRECTION.eq(reference.direction.name))
            .and(DOCUMENT.ISSUER_PARTY_KEY.eq(reference.issuerPartyKey))
            .and(DOCUMENT.DOCUMENT_NUMBER.eq(reference.precedingDocumentNumber))
            .and(DOCUMENT.DOC_UUID.ne(reference.creditNoteDocUuid))
            .fetch {
                PrecedingCandidate(
                    checkNotNull(it.get(DOCUMENT.DOC_UUID)),
                    checkNotNull(it.get(DOCUMENT.TRANSACTION_DATE)),
                )
            }
}
