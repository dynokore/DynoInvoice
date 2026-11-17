package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DocumentLocator.Companion.DOCUMENT_LOCATOR
import com.dynokore.gst.evidence.application.export.ExportClassSource
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import tools.jackson.databind.json.JsonMapper

/**
 * The export source of SOURCE_DOCUMENT_REFERENCES (INV-REQ-877, INV-AC-24168): one candidate per locator row of the
 * bound tenant whose `source_document_ref` is not null, with the tenant predicate in the WHERE clause as well as the
 * binding (CS-19), ordered by entity, period, document and version. The item name is `<docUuid>-<version>`, unique per
 * tenant; the bytes are the UTF-8 JSON object docUuid, version, entityRef, periodRef, documentNumber (only when
 * present) and sourceDocumentRef, in that order. Before reading, the reference bytes of the tenant are summed in the
 * database and a total above [maxBytes] throws [PackageSizeRefusal]; the running total of the item bytes is checked per
 * row too. [maxBytes] is configuration, never a constant.
 */
class JooqSourceDocumentReferences(
    private val dsl: DSLContext,
    private val maxBytes: Long,
) : ExportClassSource {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    override fun candidates(tenant: TenantId): List<PackageCandidate> {
        if (referenceBytes(tenant) > maxBytes) throw PackageSizeRefusal(CLASS, maxBytes)
        val candidates = mutableListOf<PackageCandidate>()
        var total = 0L
        val l = DOCUMENT_LOCATOR
        dsl
            .select(l.DOC_UUID, l.VERSION, l.ENTITY_REF, l.PERIOD_REF, l.DOCUMENT_NUMBER, l.SOURCE_DOCUMENT_REF)
            .from(l)
            .where(l.TENANT_ID.eq(tenant.value), l.SOURCE_DOCUMENT_REF.isNotNull)
            .orderBy(l.ENTITY_REF, l.PERIOD_REF, l.DOC_UUID, l.VERSION)
            .fetchSize(FETCH_SIZE)
            .fetchLazy()
            .use { cursor ->
                for (row in cursor) {
                    val docUuid = checkNotNull(row.get(l.DOC_UUID))
                    val version = checkNotNull(row.get(l.VERSION))
                    val entity = checkNotNull(row.get(l.ENTITY_REF))
                    val period = checkNotNull(row.get(l.PERIOD_REF))
                    val member = linkedMapOf<String, Any>("docUuid" to docUuid.toString(), "version" to version)
                    member["entityRef"] = entity
                    member["periodRef"] = period
                    row.get(l.DOCUMENT_NUMBER)?.let { member["documentNumber"] = it }
                    member["sourceDocumentRef"] = checkNotNull(row.get(l.SOURCE_DOCUMENT_REF))
                    val bytes = MAPPER.writeValueAsBytes(member)
                    total += bytes.size
                    if (total > maxBytes) throw PackageSizeRefusal(CLASS, maxBytes)
                    candidates +=
                        PackageCandidate(
                            tenant,
                            EntityRef(entity),
                            PeriodRef(period),
                            PackageItem(CLASS, "$docUuid-$version", bytes),
                        )
                }
            }
        return candidates
    }

    /** The reference bytes of the tenant; a null sum (no rows) counts as zero. */
    private fun referenceBytes(tenant: TenantId): Long {
        val l = DOCUMENT_LOCATOR
        val bytes =
            dsl
                .select(DSL.sum(DSL.octetLength(l.SOURCE_DOCUMENT_REF)))
                .from(l)
                .where(l.TENANT_ID.eq(tenant.value), l.SOURCE_DOCUMENT_REF.isNotNull)
                .fetchOne()
                ?.value1()
        return bytes?.toLong() ?: 0L
    }

    private companion object {
        val CLASS = PackageContentClass.SOURCE_DOCUMENT_REFERENCES
        const val FETCH_SIZE = 200
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
