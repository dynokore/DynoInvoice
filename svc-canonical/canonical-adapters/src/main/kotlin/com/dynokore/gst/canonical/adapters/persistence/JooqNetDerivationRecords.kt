package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION_NET_DERIVATION
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.application.netting.NetCreditNoteRef
import com.dynokore.gst.canonical.application.netting.NetDerivationRecords
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * TPL-11. jOOQ only, in the caller's open `canonical` transaction. One row per derived version, written with the
 * version and never edited (the runtime login holds SELECT and INSERT only). The row copies the partition columns of
 * the `document_version` row it belongs to, so the foreign key names that row in full; the credit note list is kept as
 * JSON text in request order.
 */
class JooqNetDerivationRecords(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : NetDerivationRecords {
    private val json = JsonMapper.builder().build()

    override fun record(
        docUuid: UUID,
        version: Int,
        baseVersion: Int,
        creditNotes: List<NetCreditNoteRef>,
    ) {
        val stored =
            checkNotNull(
                dsl
                    .select(DOCUMENT_VERSION.ENTITY_ID, DOCUMENT_VERSION.PARTITION_DATE)
                    .from(DOCUMENT_VERSION)
                    .where(DOCUMENT_VERSION.TENANT_ID.eq(tenants.current().value))
                    .and(DOCUMENT_VERSION.DOC_UUID.eq(docUuid))
                    .and(DOCUMENT_VERSION.VERSION.eq(version))
                    .fetchOne(),
            ) { "version $version of $docUuid is not stored: its netDerivation cannot be recorded" }
        dsl
            .insertInto(DOCUMENT_VERSION_NET_DERIVATION)
            .set(DOCUMENT_VERSION_NET_DERIVATION.DOC_UUID, docUuid)
            .set(DOCUMENT_VERSION_NET_DERIVATION.VERSION, version)
            .set(DOCUMENT_VERSION_NET_DERIVATION.TENANT_ID, tenants.current().value)
            .set(DOCUMENT_VERSION_NET_DERIVATION.ENTITY_ID, stored.get(DOCUMENT_VERSION.ENTITY_ID))
            .set(DOCUMENT_VERSION_NET_DERIVATION.PARTITION_DATE, stored.get(DOCUMENT_VERSION.PARTITION_DATE))
            .set(DOCUMENT_VERSION_NET_DERIVATION.BASE_VERSION, baseVersion)
            .set(DOCUMENT_VERSION_NET_DERIVATION.CREDIT_NOTES, listText(creditNotes))
            .execute()
    }

    override fun recordOf(
        docUuid: UUID,
        version: Int,
    ): NetDerivationFact? =
        dsl
            .select(DOCUMENT_VERSION_NET_DERIVATION.BASE_VERSION, DOCUMENT_VERSION_NET_DERIVATION.CREDIT_NOTES)
            .from(DOCUMENT_VERSION_NET_DERIVATION)
            .where(DOCUMENT_VERSION_NET_DERIVATION.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT_VERSION_NET_DERIVATION.DOC_UUID.eq(docUuid))
            .and(DOCUMENT_VERSION_NET_DERIVATION.VERSION.eq(version))
            .fetchOne()
            ?.let {
                NetDerivationFact(
                    baseVersion = checkNotNull(it.get(DOCUMENT_VERSION_NET_DERIVATION.BASE_VERSION)),
                    creditNotes = notesOf(checkNotNull(it.get(DOCUMENT_VERSION_NET_DERIVATION.CREDIT_NOTES))),
                )
            }

    private fun listText(notes: List<NetCreditNoteRef>): String =
        notes.joinToString(",", "[", "]") { "{\"docUuid\":\"${it.docUuid}\",\"version\":${it.version}}" }

    private fun notesOf(text: String): List<NetCreditNoteRef> =
        json.readTree(text).toList().map {
            NetCreditNoteRef(UUID.fromString(it.get("docUuid").asString()), it.get("version").asInt())
        }
}
