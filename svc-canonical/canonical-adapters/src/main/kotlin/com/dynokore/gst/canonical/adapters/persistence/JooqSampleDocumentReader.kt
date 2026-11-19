package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_SUBTOTAL
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.application.sample.SampleDocument
import com.dynokore.gst.canonical.application.sample.SampleDocumentReader
import com.dynokore.gst.canonical.application.sample.SampleDocumentWithoutSubtotalException
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * TPL-11. jOOQ only. The read side of the dry-run sample (INV-REQ-772 b1) over the current version of each document of
 * the entity in a window. The tenant predicate is explicit and forced row-level security stays behind it. Nothing is
 * stored: the SGD values are computed here (INV-Q-5541 A, INV-Q-5568 A, INV-Q-5634 A). The code never reads
 * `exchangeRate` and never converts.
 */
class JooqSampleDocumentReader(
    private val dsl: DSLContext,
) : SampleDocumentReader {
    private val mapper = JsonMapper.builder().build()

    override fun distinctTypeCodes(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Set<Int> =
        dsl
            .selectDistinct(DOCUMENT.DOCUMENT_TYPE_CODE)
            .from(DOCUMENT)
            .where(DOCUMENT.TENANT_ID.eq(tenantId))
            .and(DOCUMENT.ENTITY_ID.eq(entityId))
            .and(DOCUMENT.TRANSACTION_DATE.between(from, to))
            .fetch { checkNotNull(it.get(DOCUMENT.DOCUMENT_TYPE_CODE)).toInt() }
            .toSet()

    @Suppress("LongParameterList") // the signature of the window read of INV-REQ-772 b1
    override fun read(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
        limit: Int,
        afterDocUuid: UUID?,
    ): List<SampleDocument> {
        val rows =
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
                .and(DOCUMENT_VERSION.TENANT_ID.eq(DOCUMENT.TENANT_ID))
                .where(DOCUMENT.TENANT_ID.eq(tenantId))
                .and(DOCUMENT.ENTITY_ID.eq(entityId))
                .and(DOCUMENT.TRANSACTION_DATE.between(from, to))
                .and(if (afterDocUuid == null) DSL.noCondition() else DOCUMENT.DOC_UUID.gt(afterDocUuid))
                .orderBy(DOCUMENT.DOC_UUID)
                .limit(limit)
                .fetch()
        val subtotals =
            subtotalsOf(
                tenantId,
                rows.map {
                    checkNotNull(it.get(DOCUMENT.DOC_UUID)) to
                        checkNotNull(it.get(DOCUMENT.CURRENT_VERSION))
                },
            )
        return rows.map { row -> documentOf(row, subtotals) }
    }

    private fun documentOf(
        row: Record,
        subtotals: Map<UUID, List<Subtotal>>,
    ): SampleDocument {
        val docUuid = checkNotNull(row.get(DOCUMENT.DOC_UUID))
        val own = subtotals[docUuid].orEmpty()
        if (own.isEmpty()) throw SampleDocumentWithoutSubtotalException(docUuid)
        val canonical = mapper.readTree(checkNotNull(row.get(DOCUMENT_VERSION.CANONICAL)).data())
        val values = valuesOf(canonical, own)
        return SampleDocument(
            docUuid = docUuid,
            transactionDate = checkNotNull(row.get(DOCUMENT.TRANSACTION_DATE)),
            documentTypeCode = checkNotNull(row.get(DOCUMENT.DOCUMENT_TYPE_CODE)).toInt(),
            internalTaxCode = taxCodeOf(own),
            precedingDocumentDate = canonical.path("precedingDocumentDate").path("value").asString(null),
            taxableValue = values.taxable,
            gstValue = values.gst,
            sgdValuesAbsent = values.absent,
        )
    }

    private fun subtotalsOf(
        tenantId: UUID,
        keys: List<Pair<UUID, Int>>,
    ): Map<UUID, List<Subtotal>> {
        if (keys.isEmpty()) return emptyMap()
        return dsl
            .select(
                DOCUMENT_SUBTOTAL.DOC_UUID,
                DOCUMENT_SUBTOTAL.TAX_CODE,
                DOCUMENT_SUBTOTAL.TAXABLE_AMOUNT,
                DOCUMENT_SUBTOTAL.TAX_AMOUNT,
            ).from(DOCUMENT_SUBTOTAL)
            .where(DOCUMENT_SUBTOTAL.TENANT_ID.eq(tenantId))
            .and(
                DSL
                    .row(
                        DOCUMENT_SUBTOTAL.DOC_UUID,
                        DOCUMENT_SUBTOTAL.VERSION,
                    ).`in`(keys.map { DSL.row(it.first, it.second) }),
            ).fetch()
            .groupBy(
                { checkNotNull(it.get(DOCUMENT_SUBTOTAL.DOC_UUID)) },
                {
                    Subtotal(
                        checkNotNull(it.get(DOCUMENT_SUBTOTAL.TAX_CODE)),
                        checkNotNull(it.get(DOCUMENT_SUBTOTAL.TAXABLE_AMOUNT)),
                        checkNotNull(it.get(DOCUMENT_SUBTOTAL.TAX_AMOUNT)),
                    )
                },
            )
    }

    /** The code of the largest absolute taxable amount; a tie goes to the lowest code in binary order. */
    private fun taxCodeOf(subtotals: List<Subtotal>): String =
        subtotals
            .sortedWith(
                Comparator<Subtotal> { a, b -> b.taxable.abs().compareTo(a.taxable.abs()) }
                    .thenComparing { a, b -> a.taxCode.compareTo(b.taxCode) },
            ).first()
            .taxCode

    private fun valuesOf(
        canonical: JsonNode,
        subtotals: List<Subtotal>,
    ): Values {
        if (canonical.path("currency").path("value").asString(null) == SGD) {
            return Values(
                subtotals.fold(BigDecimal.ZERO) { sum, row -> sum + row.taxable }.setScale(SCALE).toPlainString(),
                subtotals.fold(BigDecimal.ZERO) { sum, row -> sum + row.tax }.setScale(SCALE).toPlainString(),
                false,
            )
        }
        val totals = canonical.path("totals").path("value")
        val taxable = totals.path("sgdTotalExcludingTax").asString(null)?.takeIf { it.isNotBlank() }
        val gst = totals.path("sgdTotalTax").asString(null)?.takeIf { it.isNotBlank() }
        return if (taxable == null || gst == null) Values(ABSENT, ABSENT, true) else Values(taxable, gst, false)
    }

    private class Subtotal(
        val taxCode: String,
        val taxable: BigDecimal,
        val tax: BigDecimal,
    )

    private class Values(
        val taxable: String,
        val gst: String,
        val absent: Boolean,
    )

    private companion object {
        const val SGD = "SGD"
        const val ABSENT = "0.00"
        const val SCALE = 2
    }
}
