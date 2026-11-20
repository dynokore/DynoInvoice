package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN
import com.dynokore.gst.canonical.application.document.PrecedingReference
import com.dynokore.gst.canonical.application.netting.NetDerivationDocuments
import com.dynokore.gst.canonical.domain.document.DerivedLine
import com.dynokore.gst.canonical.domain.document.DerivedSubtotal
import com.dynokore.gst.canonical.domain.document.Direction
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
    private val rows = NetVersionRows(dsl, tenants)
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
        val row = rows.fetch(docUuid, version)
        val function = functionOf(docUuid, version)
        if (row == null || function == null) return null
        val derived = CanonicalDerivation.derive(row.canonical)
        return NetBase(
            entityId = row.entityId,
            direction = row.direction,
            documentFunction = function,
            currency = NetCanonicalReading.currency(row.document),
            exchangeRate = NetCanonicalReading.exchangeRate(row.document),
            lines = derived.lines.map(NetCanonicalReading::lineOf),
            subtotals = derived.subtotals.map(NetCanonicalReading::subtotalOf),
            totals = NetCanonicalReading.totals(row.document),
        )
    }

    override fun creditNote(
        docUuid: UUID,
        version: Int,
    ): NetCreditNote? {
        val row = rows.fetch(docUuid, version)
        val function = functionOf(docUuid, version)
        if (row == null || function == null) return null
        val derived = CanonicalDerivation.derive(row.canonical)
        return NetCreditNote(
            docUuid = docUuid,
            version = version,
            precedingDocUuid = precedingOf(docUuid, row),
            entityId = row.entityId,
            direction = row.direction,
            documentFunction = function,
            currency = NetCanonicalReading.currency(row.document),
            exchangeRate = NetCanonicalReading.exchangeRate(row.document),
            lines = derived.lines.map(NetCanonicalReading::lineOf),
            subtotals = derived.subtotals.map(NetCanonicalReading::subtotalOf),
            totals = NetCanonicalReading.totals(row.document),
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
    ): NewVersion? =
        rows.fetch(docUuid, version)?.let { row ->
            NewVersion(
                canonical = row.canonical,
                scopeVerdict = row.record.get(DOCUMENT_VERSION.SCOPE_VERDICT)?.let { ScopeVerdict.valueOf(it) },
                scopePackVersion = row.record.get(DOCUMENT_VERSION.SCOPE_PACK_VERSION),
                mappingPackVersion = row.record.get(DOCUMENT_VERSION.MAPPING_PACK_VERSION),
                mdePackVersion = row.record.get(DOCUMENT_VERSION.MDE_PACK_VERSION),
                specificationVersion =
                    row.record.get(DOCUMENT_VERSION.SPECIFICATION_VERSION)?.let { SpecificationVersion.valueOf(it) },
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
    ): NetEventBasis? = rows.basisOf(docUuid, version)

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
        row: NetVersionRow,
    ): UUID? {
        val number =
            row.document
                .path("precedingDocumentNumber")
                .path("value")
                .asString(null)
        val date =
            row.document
                .path("precedingDocumentDate")
                .path("value")
                .asString(null)
                ?.let { LocalDate.parse(it) }
        if (number == null || number.length !in 1..MAX_PRECEDING_NUMBER) return null
        val reference =
            PrecedingReference(
                docUuid,
                row.entityId,
                row.direction,
                checkNotNull(row.record.get(DOCUMENT.ISSUER_PARTY_KEY)),
                number,
                date,
            )
        return (PrecedingDocumentSelector.select(lookup.candidates(reference), date) as? PrecedingResolution.Resolved)
            ?.docUuid
    }

    private companion object {
        const val MAX_PRECEDING_NUMBER = 64
    }
}

/** One stored version joined with its document row: the record, the parsed canonical document and its text. */
internal class NetVersionRow(
    val record: Record,
    val document: JsonNode,
    val canonical: String,
) {
    val entityId: UUID get() = checkNotNull(record.get(DOCUMENT.ENTITY_ID))
    val direction: Direction get() = Direction.valueOf(checkNotNull(record.get(DOCUMENT.DIRECTION)))
}

/** The tenant-bound reads of the version row and of the ingestion and period facts around it. */
internal class NetVersionRows(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) {
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun fetch(
        docUuid: UUID,
        version: Int,
    ): NetVersionRow? {
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
                .fetchOne()
        val canonical = record?.get(DOCUMENT_VERSION.CANONICAL)?.data()
        if (record == null || canonical == null) return null
        return NetVersionRow(record, mapper.readTree(canonical), canonical)
    }

    /** The event basis of a version; null when a fact is not stored (see [JooqNetDerivationDocuments.eventBasisOf]). */
    fun basisOf(
        docUuid: UUID,
        version: Int,
    ): NetEventBasis? {
        val row = fetch(docUuid, version) ?: return null
        val period = periodOf(row)
        val kind = kindOf(docUuid, version)
        val verdict = row.record.get(DOCUMENT_VERSION.SCOPE_VERDICT)
        return if (period == null || kind == null || verdict == null) {
            null
        } else {
            NetEventBasis(
                entityRef = checkNotNull(period.get(PERIOD_SPAN.ENTITY_REF)),
                prescribedPeriodRef = checkNotNull(period.get(PERIOD_SPAN.PERIOD_REF)),
                scopeVerdict = verdict,
                direction = row.direction.name,
                receivedViaNetwork = kind == "RECEIPT",
            )
        }
    }

    /** The span (entity reference and period reference) of the prescribed period of the document, if it has one. */
    private fun periodOf(row: NetVersionRow): Record? =
        row.record.get(DOCUMENT.PRESCRIBED_PERIOD_ID)?.let { periodId ->
            dsl
                .select(PERIOD_SPAN.ENTITY_REF, PERIOD_SPAN.PERIOD_REF)
                .from(PERIOD_SPAN)
                .where(PERIOD_SPAN.TENANT_ID.eq(tenants.current().value))
                .and(PERIOD_SPAN.PERIOD_ID.eq(periodId))
                .fetchOne()
        }

    /** The kind of the latest ingestion row of the document at or below [version]. */
    private fun kindOf(
        docUuid: UUID,
        version: Int,
    ): String? =
        dsl
            .select(INBOUND_EVENT.EVENT_KIND)
            .from(INBOUND_EVENT)
            .where(INBOUND_EVENT.TENANT_ID.eq(tenants.current().value))
            .and(INBOUND_EVENT.DOC_UUID.eq(docUuid))
            .and(INBOUND_EVENT.VERSION.le(version))
            .orderBy(INBOUND_EVENT.VERSION.desc())
            .limit(1)
            .fetchOne(INBOUND_EVENT.EVENT_KIND)
}

/** Reads the header facts and the stated totals of a canonical document; money is a decimal string (API-12). */
private object NetCanonicalReading {
    fun currency(document: JsonNode): String = document.path("currency").path("value").asString("")

    fun exchangeRate(document: JsonNode): BigDecimal? =
        document
            .path("exchangeRate")
            .path("value")
            .asString(null)
            ?.let { BigDecimal(it) }

    fun totals(document: JsonNode): NetTotals {
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

    fun lineOf(line: DerivedLine) =
        NetLine(line.lineNumber, line.quantity, line.unitPrice, line.lineAmount, line.taxCode, line.taxAmount)

    fun subtotalOf(subtotal: DerivedSubtotal) =
        NetSubtotal(subtotal.taxCode, subtotal.taxableAmount, subtotal.taxAmount)

    private fun money(
        node: JsonNode,
        member: String,
    ): BigDecimal? = node.path(member).asString(null)?.let { BigDecimal(it) }
}
