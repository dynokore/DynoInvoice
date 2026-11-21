package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.events.AcknowledgedVersions
import com.dynokore.gst.canonical.adapters.events.PopulationDocument
import com.dynokore.gst.canonical.adapters.events.ReprocessingPackType
import com.dynokore.gst.canonical.adapters.events.ReprocessingPopulation
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_SUBTOTAL
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SUBMISSION_ACKNOWLEDGEMENT
import com.dynokore.gst.canonical.application.sample.SampleDocumentWithoutSubtotalException
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * TPL-11. jOOQ only. The read side of a reprocessing run (INV-REQ-1003) over the current version of each document of
 * one entity, and the acknowledgement lookup in the canonical-local replica `canonical.submission_acknowledgement`.
 * The tenant predicate is explicit and forced row-level security stays behind it. Nothing is written. The SGD values
 * are the ones of the dry-run sample item (never converted, `exchangeRate` never read).
 */
class JooqReprocessingPopulation(
    private val dsl: DSLContext,
) : ReprocessingPopulation,
    AcknowledgedVersions {
    private val mapper = JsonMapper.builder().build()

    @Suppress("LongParameterList") // the signature of the population read of INV-REQ-1003
    override fun read(
        tenantId: UUID,
        entityId: UUID,
        packType: ReprocessingPackType,
        badPackVersion: String,
        effectiveFrom: LocalDate,
        limit: Int,
        afterDocUuid: UUID?,
    ): List<PopulationDocument> {
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
                .and(DOCUMENT.TRANSACTION_DATE.ge(effectiveFrom))
                .and(packCondition(packType, badPackVersion))
                .and(if (afterDocUuid == null) DSL.noCondition() else DOCUMENT.DOC_UUID.gt(afterDocUuid))
                .orderBy(DOCUMENT.DOC_UUID)
                .limit(limit)
                .fetch()
        val subtotals =
            subtotalsOf(
                tenantId,
                rows.map { checkNotNull(it.get(DOCUMENT.DOC_UUID)) to checkNotNull(it.get(DOCUMENT.CURRENT_VERSION)) },
            )
        return rows.map { documentOf(it, subtotals) }
    }

    /** The version stored in the column of the pack type equals the bad version; a null column is never equal. */
    private fun packCondition(
        packType: ReprocessingPackType,
        badPackVersion: String,
    ): Condition =
        when (packType) {
            ReprocessingPackType.SCOPE -> {
                DOCUMENT_VERSION.SCOPE_PACK_VERSION.eq(badPackVersion)
            }

            ReprocessingPackType.MAPPING -> {
                DOCUMENT_VERSION.MAPPING_PACK_VERSION.eq(badPackVersion)
            }

            ReprocessingPackType.MDE -> {
                DOCUMENT_VERSION.MDE_PACK_VERSION
                    .isNotNull
                    .and(DOCUMENT_VERSION.MDE_PACK_VERSION.eq(badPackVersion))
            }
        }

    override fun acknowledged(
        tenantId: UUID,
        keys: List<Pair<UUID, Int>>,
    ): Set<Pair<UUID, Int>> {
        if (keys.isEmpty()) return emptySet()
        return dsl
            .select(SUBMISSION_ACKNOWLEDGEMENT.DOC_UUID, SUBMISSION_ACKNOWLEDGEMENT.VERSION)
            .from(SUBMISSION_ACKNOWLEDGEMENT)
            .where(SUBMISSION_ACKNOWLEDGEMENT.TENANT_ID.eq(tenantId))
            .and(
                DSL
                    .row(SUBMISSION_ACKNOWLEDGEMENT.DOC_UUID, SUBMISSION_ACKNOWLEDGEMENT.VERSION)
                    .`in`(keys.map { DSL.row(it.first, it.second) }),
            ).fetch {
                checkNotNull(it.get(SUBMISSION_ACKNOWLEDGEMENT.DOC_UUID)) to
                    checkNotNull(it.get(SUBMISSION_ACKNOWLEDGEMENT.VERSION))
            }.toSet()
    }

    private fun documentOf(
        row: Record,
        subtotals: Map<UUID, List<Subtotal>>,
    ): PopulationDocument {
        val docUuid = checkNotNull(row.get(DOCUMENT.DOC_UUID))
        val own = subtotals[docUuid].orEmpty()
        if (own.isEmpty()) throw SampleDocumentWithoutSubtotalException(docUuid)
        val canonical = mapper.readTree(checkNotNull(row.get(DOCUMENT_VERSION.CANONICAL)).data())
        val values = valuesOf(canonical, own)
        return PopulationDocument(
            docUuid = docUuid,
            version = checkNotNull(row.get(DOCUMENT.CURRENT_VERSION)),
            transactionDate = checkNotNull(row.get(DOCUMENT.TRANSACTION_DATE)),
            documentTypeCode = checkNotNull(row.get(DOCUMENT.DOCUMENT_TYPE_CODE)).toInt(),
            internalTaxCode = taxCodeOf(own),
            precedingDocumentDate = canonical.path("precedingDocumentDate").path("value").asString(null),
            taxableValue = values.taxable,
            gstValue = values.gst,
            sgdValuesAbsent = values.absent,
            supplyType =
                canonical
                    .path("supplyType")
                    .path("value")
                    .asString(null)
                    ?.takeIf { it.isNotEmpty() },
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
                    .row(DOCUMENT_SUBTOTAL.DOC_UUID, DOCUMENT_SUBTOTAL.VERSION)
                    .`in`(keys.map { DSL.row(it.first, it.second) }),
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
