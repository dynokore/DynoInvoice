package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN
import com.dynokore.gst.canonical.application.document.PrecedingReference
import com.dynokore.gst.canonical.application.netting.NetDerivationDocuments
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DerivedLine
import com.dynokore.gst.canonical.domain.document.DerivedSubtotal
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.PrecedingDocumentSelector
import com.dynokore.gst.canonical.domain.document.PrecedingResolution
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.NetBase
import com.dynokore.gst.canonical.domain.netting.NetCreditNote
import com.dynokore.gst.canonical.domain.netting.NetLine
import com.dynokore.gst.canonical.domain.netting.NetSubtotal
import com.dynokore.gst.canonical.domain.netting.NetTotals
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The stored facts a `CanonicalVersionCreated` event of a derived version is built from: the entity and the prescribed
 * period of the document, the scope verdict of the base version, its direction and how it reached the platform.
 */
data class NetEventBasis(
    val entityRef: String,
    val prescribedPeriodRef: String,
    val scopeVerdict: String,
    val direction: String,
    val receivedViaNetwork: Boolean,
)

/**
 * TPL-11. jOOQ only, read-only, in the caller's open `canonical` transaction; every read names the session's tenant, so
 * a document of another tenant is unknown. The `documentFunction` of a version is the one the ingestion recorded for
 * it in `inbound_event` (the latest row at or below the version): a document with no such row is unknown, so netting
 * fails closed on a document whose function nobody asserted. `precedingDocUuid` is resolved from the credit note's
 * `precedingDocumentNumber` by the same selector as the CanonicalVersionCreated event (INV-REQ-709).
 */
class JooqNetDerivationDocuments(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : NetDerivationDocuments {
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private val lookup = JooqPrecedingDocumentLookup(dsl)

    override fun currentVersion(docUuid: UUID): Int? =
        dsl
            .select(DOCUMENT.CURRENT_VERSION)
            .from(DOCUMENT)
            .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT.DOC_UUID.eq(docUuid))
            .fetchOne(DOCUMENT.CURRENT_VERSION)

    /** The transaction date of the document (the one of its current version), or null when it is unknown. */
    fun transactionDateOf(docUuid: UUID): LocalDate? =
        dsl
            .select(DOCUMENT.TRANSACTION_DATE)
            .from(DOCUMENT)
            .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT.DOC_UUID.eq(docUuid))
            .fetchOne(DOCUMENT.TRANSACTION_DATE)

    override fun base(
        docUuid: UUID,
        version: Int,
    ): NetBase? {
        val row = versionRow(docUuid, version) ?: return null
        val function = functionOf(docUuid, version) ?: return null
        val rows = CanonicalDerivation.derive(row.canonical)
        return NetBase(
            entityId = row.entityId,
            direction = row.direction,
            documentFunction = function,
            currency = currency(row.document),
            exchangeRate = exchangeRate(row.document),
            lines = rows.lines.map(::lineOf),
            subtotals = rows.subtotals.map(::subtotalOf),
            totals = totals(row.document),
        )
    }

    override fun creditNote(
        docUuid: UUID,
        version: Int,
    ): NetCreditNote? {
        val row = versionRow(docUuid, version) ?: return null
        val function = functionOf(docUuid, version) ?: return null
        val rows = CanonicalDerivation.derive(row.canonical)
        return NetCreditNote(
            docUuid = docUuid,
            version = version,
            precedingDocUuid = precedingOf(docUuid, row),
            entityId = row.entityId,
            direction = row.direction,
            documentFunction = function,
            currency = currency(row.document),
            exchangeRate = exchangeRate(row.document),
            lines = rows.lines.map(::lineOf),
            subtotals = rows.subtotals.map(::subtotalOf),
            totals = totals(row.document),
        )
    }

    /**
     * The stored version as the base of a derived one: the canonical text and the pack and specification versions,
     * with the document's transaction date and [createdBy] as the acting identity. Null when the version is unknown.
     */
    fun versionAsBase(
        docUuid: UUID,
        version: Int,
        createdBy: String,
    ): NewVersion? {
        val row = versionRow(docUuid, version) ?: return null
        return NewVersion(
            canonical = row.canonical,
            scopeVerdict = row.record.get(DOCUMENT_VERSION.SCOPE_VERDICT)?.let { ScopeVerdict.valueOf(it) },
            scopePackVersion = row.record.get(DOCUMENT_VERSION.SCOPE_PACK_VERSION),
            mappingPackVersion = row.record.get(DOCUMENT_VERSION.MAPPING_PACK_VERSION),
            mdePackVersion = row.record.get(DOCUMENT_VERSION.MDE_PACK_VERSION),
            specificationVersion = row.record.get(DOCUMENT_VERSION.SPECIFICATION_VERSION)?.let { SpecificationVersion.valueOf(it) },
            transactionDate = checkNotNull(row.record.get(DOCUMENT.TRANSACTION_DATE)),
            createdBy = createdBy,
        )
    }

    /**
     * The event basis of a version, or null when a fact is not stored: the document has no prescribed period (or the
     * period has no span), the version has no scope verdict, or no ingestion row names how it reached the platform.
     * `RECEIPT` is a network receipt, `POSTING` the buyer's own posting (contract: RECEIVED_VIA_NETWORK / otherwise).
     */
    fun eventBasisOf(
        docUuid: UUID,
        version: Int,
    ): NetEventBasis? {
        val row = versionRow(docUuid, version) ?: return null
        val periodId = row.record.get(DOCUMENT.PRESCRIBED_PERIOD_ID) ?: return null
        val period =
            dsl
                .select(PERIOD_SPAN.ENTITY_REF, PERIOD_SPAN.PERIOD_REF)
                .from(PERIOD_SPAN)
                .where(PERIOD_SPAN.TENANT_ID.eq(tenants.current().value))
                .and(PERIOD_SPAN.PERIOD_ID.eq(periodId))
                .fetchOne() ?: return null
        val kind =
            dsl
                .select(INBOUND_EVENT.EVENT_KIND)
                .from(INBOUND_EVENT)
                .where(INBOUND_EVENT.TENANT_ID.eq(tenants.current().value))
                .and(INBOUND_EVENT.DOC_UUID.eq(docUuid))
                .and(INBOUND_EVENT.VERSION.le(version))
                .orderBy(INBOUND_EVENT.VERSION.desc())
                .limit(1)
                .fetchOne(INBOUND_EVENT.EVENT_KIND) ?: return null
        return NetEventBasis(
            entityRef = checkNotNull(period.get(PERIOD_SPAN.ENTITY_REF)),
            prescribedPeriodRef = checkNotNull(period.get(PERIOD_SPAN.PERIOD_REF)),
            scopeVerdict = row.record.get(DOCUMENT_VERSION.SCOPE_VERDICT) ?: return null,
            direction = row.direction.name,
            receivedViaNetwork = kind == "RECEIPT",
        )
    }

    private class VersionRow(
        val record: Record,
        val document: JsonNode,
        val canonical: String,
    ) {
        val entityId: UUID get() = checkNotNull(record.get(DOCUMENT.ENTITY_ID))
        val direction: Direction get() = Direction.valueOf(checkNotNull(record.get(DOCUMENT.DIRECTION)))
    }

    private fun versionRow(
        docUuid: UUID,
        version: Int,
    ): VersionRow? {
        val record =
            dsl
                .select(
                    DOCUMENT.ENTITY_ID,
                    DOCUMENT.DIRECTION,
                    DOCUMENT.ISSUER_PARTY_KEY,
                    DOCUMENT.TRANSACTION_DATE,
                    DOCUMENT.PRESCRIBED_PERIOD_ID,
                    DOCUMENT_VERSION.CANONICAL,
                    DOCUMENT_VERSION.SCOPE_VERDICT,
                    DOCUMENT_VERSION.SCOPE_PACK_VERSION,
                    DOCUMENT_VERSION.MAPPING_PACK_VERSION,
                    DOCUMENT_VERSION.MDE_PACK_VERSION,
                    DOCUMENT_VERSION.SPECIFICATION_VERSION,
                ).from(DOCUMENT)
                .join(DOCUMENT_VERSION)
                .on(DOCUMENT_VERSION.DOC_UUID.eq(DOCUMENT.DOC_UUID))
                .and(DOCUMENT_VERSION.PARTITION_DATE.eq(DOCUMENT.PARTITION_DATE))
                .and(DOCUMENT_VERSION.ENTITY_ID.eq(DOCUMENT.ENTITY_ID))
                .and(DOCUMENT_VERSION.TENANT_ID.eq(DOCUMENT.TENANT_ID))
                .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
                .and(DOCUMENT.DOC_UUID.eq(docUuid))
                .and(DOCUMENT_VERSION.VERSION.eq(version))
                .fetchOne() ?: return null
        val canonical = checkNotNull(record.get(DOCUMENT_VERSION.CANONICAL)).data()
        return VersionRow(record, mapper.readTree(canonical), canonical)
    }

    private fun functionOf(
        docUuid: UUID,
        version: Int,
    ): DocumentFunction? =
        dsl
            .select(INBOUND_EVENT.DOCUMENT_FUNCTION)
            .from(INBOUND_EVENT)
            .where(INBOUND_EVENT.TENANT_ID.eq(tenants.current().value))
            .and(INBOUND_EVENT.DOC_UUID.eq(docUuid))
            .and(INBOUND_EVENT.VERSION.le(version))
            .orderBy(INBOUND_EVENT.VERSION.desc())
            .limit(1)
            .fetchOne(INBOUND_EVENT.DOCUMENT_FUNCTION)
            ?.let { DocumentFunction.valueOf(it) }

    private fun precedingOf(
        docUuid: UUID,
        row: VersionRow,
    ): UUID? {
        val number = row.document.path("precedingDocumentNumber").path("value").asString(null)
        if (number == null || number.length !in 1..MAX_PRECEDING_NUMBER) return null
        val date = row.document.path("precedingDocumentDate").path("value").asString(null)?.let { LocalDate.parse(it) }
        val reference =
            PrecedingReference(
                docUuid,
                row.entityId,
                row.direction,
                checkNotNull(row.record.get(DOCUMENT.ISSUER_PARTY_KEY)),
                number,
                date,
            )
        val resolution = PrecedingDocumentSelector.select(lookup.candidates(reference), date)
        return (resolution as? PrecedingResolution.Resolved)?.docUuid
    }

    private fun currency(document: JsonNode): String = document.path("currency").path("value").asString("")

    private fun exchangeRate(document: JsonNode): BigDecimal? =
        document.path("exchangeRate").path("value").asString(null)?.let { BigDecimal(it) }

    private fun totals(document: JsonNode): NetTotals {
        val value = document.path("totals").path("value")
        return NetTotals(
            excludingTax = money(value, "totalExcludingTax") ?: error("totals.value.totalExcludingTax is missing"),
            tax = money(value, "totalTax") ?: error("totals.value.totalTax is missing"),
            includingTax = money(value, "totalIncludingTax") ?: error("totals.value.totalIncludingTax is missing"),
            sgdExcludingTax = money(value, "sgdTotalExcludingTax"),
            sgdTax = money(value, "sgdTotalTax"),
            sgdIncludingTax = money(value, "sgdTotalIncludingTax"),
        )
    }

    private fun money(
        node: JsonNode,
        member: String,
    ): BigDecimal? = node.path(member).asString(null)?.let { BigDecimal(it) }

    private fun lineOf(line: DerivedLine) =
        NetLine(line.lineNumber, line.quantity, line.unitPrice, line.lineAmount, line.taxCode, line.taxAmount)

    private fun subtotalOf(subtotal: DerivedSubtotal) =
        NetSubtotal(subtotal.taxCode, subtotal.taxableAmount, subtotal.taxAmount)

    private companion object {
        const val MAX_PRECEDING_NUMBER = 64
    }
}
