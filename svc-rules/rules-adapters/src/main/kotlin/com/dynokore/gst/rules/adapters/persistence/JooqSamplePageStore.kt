package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_SAMPLE_ITEM
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_SAMPLE_PAGE
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Record
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The pages of the historical sample of a dry run over `rules.dry_run_sample_page` and `rules.dry_run_sample_item`
 * (INV-T-19903, INV-Q-4870 B, INV-Q-4995), jOOQ only. Each member is stored as received: `periodRef` and `docUuid`
 * are nullable text with no check, so an item without them, or with a malformed one, is kept and returned as it came;
 * nothing is derived, generated, corrected or defaulted here. Row-level security hides every other tenant's rows.
 * The pages live only until the run ends: [release] deletes them (INV-REQ-1196).
 */
class JooqSamplePageStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) {
    /**
     * Stores [page] of [dryRunId] for [tenant] in the caller's open transaction (the inbox consumer's, so the rows,
     * the inbox row and the offset commit together). A page already held under the same tenant, dry-run id and page
     * number is kept as first stored. True when the page was stored now, false when it was a redelivery.
     */
    fun record(
        tenant: TenantId,
        dryRunId: String,
        page: SamplePage,
        receivedAt: Instant,
    ): Boolean {
        val rows = page.documents.map(SampleItemColumns::of)
        val stored =
            dsl
                .insertInto(DRY_RUN_SAMPLE_PAGE)
                .set(DRY_RUN_SAMPLE_PAGE.TENANT_ID, tenant.value)
                .set(DRY_RUN_SAMPLE_PAGE.DRY_RUN_ID, dryRunId)
                .set(DRY_RUN_SAMPLE_PAGE.PAGE_NUMBER, page.number)
                .set(DRY_RUN_SAMPLE_PAGE.LAST_PAGE, page.lastPage)
                .set(DRY_RUN_SAMPLE_PAGE.RECEIVED_AT, receivedAt.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute()
        if (stored == 0) return false
        rows.forEachIndexed { index, row -> insertItem(tenant, dryRunId, page.number, index + 1, row) }
        return true
    }

    /** The pages stored for [dryRunId] of the bound tenant, by page number, every item with its members as received. */
    fun pages(dryRunId: String): List<SamplePage> {
        val tenant = tenants.current().value
        val items =
            dsl
                .selectFrom(DRY_RUN_SAMPLE_ITEM)
                .where(DRY_RUN_SAMPLE_ITEM.TENANT_ID.eq(tenant))
                .and(DRY_RUN_SAMPLE_ITEM.DRY_RUN_ID.eq(dryRunId))
                .orderBy(DRY_RUN_SAMPLE_ITEM.PAGE_NUMBER, DRY_RUN_SAMPLE_ITEM.POSITION)
                .fetch()
                .groupBy { checkNotNull(it.get(DRY_RUN_SAMPLE_ITEM.PAGE_NUMBER)) }
        return dsl
            .selectFrom(DRY_RUN_SAMPLE_PAGE)
            .where(DRY_RUN_SAMPLE_PAGE.TENANT_ID.eq(tenant))
            .and(DRY_RUN_SAMPLE_PAGE.DRY_RUN_ID.eq(dryRunId))
            .orderBy(DRY_RUN_SAMPLE_PAGE.PAGE_NUMBER)
            .fetch()
            .map {
                val number = checkNotNull(it.get(DRY_RUN_SAMPLE_PAGE.PAGE_NUMBER))
                SamplePage(
                    number = number,
                    lastPage = checkNotNull(it.get(DRY_RUN_SAMPLE_PAGE.LAST_PAGE)),
                    documents = items[number].orEmpty().map(SampleItemColumns::member),
                )
            }
    }

    /** Deletes the page and item rows of [dryRunId] of the bound tenant; a run without rows is not an error. */
    fun release(dryRunId: String) {
        val tenant = tenants.current().value
        dsl
            .deleteFrom(DRY_RUN_SAMPLE_ITEM)
            .where(DRY_RUN_SAMPLE_ITEM.TENANT_ID.eq(tenant))
            .and(DRY_RUN_SAMPLE_ITEM.DRY_RUN_ID.eq(dryRunId))
            .execute()
        dsl
            .deleteFrom(DRY_RUN_SAMPLE_PAGE)
            .where(DRY_RUN_SAMPLE_PAGE.TENANT_ID.eq(tenant))
            .and(DRY_RUN_SAMPLE_PAGE.DRY_RUN_ID.eq(dryRunId))
            .execute()
    }

    private fun insertItem(
        tenant: TenantId,
        dryRunId: String,
        pageNumber: Int,
        position: Int,
        row: SampleItemColumns,
    ) {
        dsl
            .insertInto(DRY_RUN_SAMPLE_ITEM)
            .set(DRY_RUN_SAMPLE_ITEM.TENANT_ID, tenant.value)
            .set(DRY_RUN_SAMPLE_ITEM.DRY_RUN_ID, dryRunId)
            .set(DRY_RUN_SAMPLE_ITEM.PAGE_NUMBER, pageNumber)
            .set(DRY_RUN_SAMPLE_ITEM.POSITION, position)
            .set(DRY_RUN_SAMPLE_ITEM.ENTITY_REF, row.entityRef)
            .set(DRY_RUN_SAMPLE_ITEM.TRANSACTION_DATE, row.transactionDate)
            .set(DRY_RUN_SAMPLE_ITEM.INTERNAL_TAX_CODE, row.internalTaxCode)
            .set(DRY_RUN_SAMPLE_ITEM.DOCUMENT_FUNCTION, row.documentFunction)
            .set(DRY_RUN_SAMPLE_ITEM.SUPPLY_TYPE, row.supplyType)
            .set(DRY_RUN_SAMPLE_ITEM.PRECEDING_DOCUMENT_DATE, row.precedingDocumentDate)
            .set(DRY_RUN_SAMPLE_ITEM.TAXABLE_VALUE, row.taxableValue)
            .set(DRY_RUN_SAMPLE_ITEM.GST_VALUE, row.gstValue)
            .set(DRY_RUN_SAMPLE_ITEM.SGD_VALUES_ABSENT, row.sgdValuesAbsent)
            .set(DRY_RUN_SAMPLE_ITEM.PERIOD_REF, row.periodRef)
            .set(DRY_RUN_SAMPLE_ITEM.DOC_UUID, row.docUuid)
            .execute()
    }
}

/**
 * The columns of one page item. The members the contract requires must be present and well typed, else the event breaks
 * the contract ([ContractViolation]); `periodRef` and `docUuid` are taken as they are when they are strings, whatever they hold; a non-string is refused.
 */
@Suppress("LongParameterList") // one parameter per column of the item row
private class SampleItemColumns(
    val entityRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val documentFunction: String,
    val supplyType: String?,
    val precedingDocumentDate: LocalDate?,
    val taxableValue: BigDecimal,
    val gstValue: BigDecimal,
    val sgdValuesAbsent: Boolean?,
    val periodRef: String?,
    val docUuid: String?,
) {
    companion object {
        private val MONEY = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")

        fun of(raw: Map<String, Any?>) =
            SampleItemColumns(
                entityRef = text(raw, "entityRef"),
                transactionDate = date(raw, "transactionDate") ?: missing("transactionDate"),
                internalTaxCode = text(raw, "internalTaxCode"),
                documentFunction = text(raw, "documentFunction"),
                supplyType = optionalText(raw, "supplyType"),
                precedingDocumentDate = date(raw, "precedingDocumentDate"),
                taxableValue = money(raw, "taxableValue"),
                gstValue = money(raw, "gstValue"),
                sgdValuesAbsent = flag(raw),
                periodRef = rawText(raw, "periodRef"),
                docUuid = rawText(raw, "docUuid"),
            )

        /** The page item as the producer published it: the members that were present, absent ones left out. */
        fun member(r: Record): Map<String, Any?> =
            buildMap {
                put("entityRef", r.get(DRY_RUN_SAMPLE_ITEM.ENTITY_REF))
                put("transactionDate", r.get(DRY_RUN_SAMPLE_ITEM.TRANSACTION_DATE)?.toString())
                put("internalTaxCode", r.get(DRY_RUN_SAMPLE_ITEM.INTERNAL_TAX_CODE))
                put("documentFunction", r.get(DRY_RUN_SAMPLE_ITEM.DOCUMENT_FUNCTION))
                r.get(DRY_RUN_SAMPLE_ITEM.SUPPLY_TYPE)?.let { put("supplyType", it) }
                r.get(DRY_RUN_SAMPLE_ITEM.PRECEDING_DOCUMENT_DATE)?.let { put("precedingDocumentDate", it.toString()) }
                put("taxableValue", r.get(DRY_RUN_SAMPLE_ITEM.TAXABLE_VALUE)?.toPlainString())
                put("gstValue", r.get(DRY_RUN_SAMPLE_ITEM.GST_VALUE)?.toPlainString())
                r.get(DRY_RUN_SAMPLE_ITEM.SGD_VALUES_ABSENT)?.let { put("sgdValuesAbsent", it) }
                r.get(DRY_RUN_SAMPLE_ITEM.PERIOD_REF)?.let { put("periodRef", it) }
                r.get(DRY_RUN_SAMPLE_ITEM.DOC_UUID)?.let { put("docUuid", it) }
            }

        /** A present, non-null member must be a JSON string; it is never converted (validate at the edge). */
        private fun rawText(
            raw: Map<String, Any?>,
            name: String,
        ): String? =
            raw[name]?.let { it as? String ?: throw ContractViolation("page item member $name is not a string") }

        private fun missing(name: String): Nothing = throw ContractViolation("page item member $name is missing")

        private fun text(
            raw: Map<String, Any?>,
            name: String,
        ): String = (raw[name] as? String)?.takeIf { it.isNotEmpty() } ?: missing(name)

        private fun optionalText(
            raw: Map<String, Any?>,
            name: String,
        ): String? = if (raw[name] == null) null else text(raw, name)

        private fun date(
            raw: Map<String, Any?>,
            name: String,
        ): LocalDate? =
            raw[name]?.let {
                try {
                    LocalDate.parse(it as? String ?: missing(name))
                } catch (e: DateTimeParseException) {
                    throw ContractViolation("page item member $name is not a date", e)
                }
            }

        private fun money(
            raw: Map<String, Any?>,
            name: String,
        ): BigDecimal {
            val value = raw[name] as? String ?: missing(name)
            if (!MONEY.matches(value)) throw ContractViolation("page item member $name is not a decimal")
            return BigDecimal(value)
        }

        private fun flag(raw: Map<String, Any?>): Boolean? =
            raw["sgdValuesAbsent"]?.let {
                it as? Boolean ?: throw ContractViolation("page item member sgdValuesAbsent is not a boolean")
            }
    }
}
