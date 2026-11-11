package com.dynokore.gst.rules.domain.dryrun

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** The page item at a position could not be used; the run ends SAMPLE_UNAVAILABLE naming it (fail closed). */
data class ItemRefusal(
    val pageNumber: Int,
    val position: Int,
    val reason: String,
) {
    override fun toString() = "page $pageNumber, item $position: $reason"
}

sealed interface ItemReading {
    data class Read(
        val document: SampledDocument,
    ) : ItemReading

    data class Refused(
        val refusal: ItemRefusal,
    ) : ItemReading
}

/**
 * Reads the page items and computes the delta. Pure: no clock, no port. The period of a document is the `periodRef` of
 * its page item and its identifier the `docUuid`; none is derived or generated (coordinator ruling of INV-T-4974).
 */
object DryRunComputation {
    private val ZERO = BigDecimal("0.00")

    fun changeTypeOf(
        old: DocumentOutcome,
        new: DocumentOutcome,
    ): ChangeType? =
        when {
            old == new -> null
            new.refused && !old.refused -> ChangeType.NEWLY_REFUSED
            old.refused && !new.refused -> ChangeType.NEWLY_ACCEPTED
            else -> ChangeType.CODE_CHANGED
        }

    /** A credit note whose two dates lie on different sides of [effectiveFrom] (before vs on or after). */
    fun straddles(
        doc: ChangedDocument,
        effectiveFrom: LocalDate,
    ): Boolean {
        val preceding = doc.precedingDocumentDate ?: return false
        return doc.documentFunction == "ADJUSTMENT" &&
            doc.transactionDate.isBefore(effectiveFrom) != preceding.isBefore(effectiveFrom)
    }

    /** The delta of the documents with their [outcomes] (old, new), for a pack effective from [effectiveFrom]. */
    fun delta(
        outcomes: List<Triple<SampledDocument, DocumentOutcome, DocumentOutcome>>,
        effectiveFrom: LocalDate,
    ): DryRunDelta {
        val changed = outcomes.mapNotNull { (d, old, new) -> changedOf(d, old, new) }
        val (straddling, general) = changed.partition { straddles(it, effectiveFrom) }
        return DryRunDelta(
            changedCount = changed.size,
            documents = changed,
            changeTypeTotals =
                ChangeType.entries.map { t ->
                    changeTypeTotal(t, changed.filter { it.changeType == t })
                },
            byPeriod = periods(changed),
            straddlingCreditNotes = straddling.map(::listing),
            sample = general.map(::listing),
        )
    }

    private val SUPPLY_ORDER = listOf("STANDARD_RATED", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE", "IMPORTS")

    /**
     * The totals of the changed [docs] by GST supply type: one item per type with a changed document, in the order
     * of the five GST-return categories and then any other member ascending by code unit. A flagged document counts
     * in `documentCount` and `valuesAbsentCount` and in neither sum. A document without a supply type is a fault.
     */
    fun supplyTypeTotals(docs: List<ChangedDocument>): List<SupplyTypeTotal> =
        docs
            .groupBy { checkNotNull(it.supplyType) { "changed document ${it.docUuid} has no supply type" } }
            .toList()
            .sortedWith(compareBy({ supplyRank(it.first) }, { it.first }))
            .map { (type, group) ->
                val t = total(group)
                SupplyTypeTotal(type, t.documentCount, t.taxableValue, t.gstValue, t.valuesAbsentCount)
            }

    private fun supplyRank(type: String): Int = SUPPLY_ORDER.indexOf(type).takeIf { it >= 0 } ?: SUPPLY_ORDER.size

    private fun changedOf(
        d: SampledDocument,
        old: DocumentOutcome,
        new: DocumentOutcome,
    ): ChangedDocument? {
        val type = changeTypeOf(old, new) ?: return null
        return ChangedDocument(
            d.docUuid,
            d.periodRef,
            d.transactionDate,
            d.precedingDocumentDate,
            d.documentFunction,
            d.supplyType,
            old,
            new,
            type,
            d.taxableValue.toPlainString(),
            d.gstValue.toPlainString(),
            d.sgdValuesAbsent,
        )
    }

    private fun changeTypeTotal(
        type: ChangeType,
        docs: List<ChangedDocument>,
    ): ChangeTypeTotal =
        total(docs).let { ChangeTypeTotal(type, it.documentCount, it.taxableValue, it.gstValue, it.valuesAbsentCount) }

    /** One item per period, in the order of the earliest transaction date of its changed documents (INV-REQ-686). */
    private fun periods(changed: List<ChangedDocument>): List<PeriodDelta> =
        changed
            .groupBy { it.periodRef }
            .toList()
            .sortedWith(compareBy({ (_, docs) -> docs.minOf { it.transactionDate } }, { it.first }))
            .map { (ref, docs) ->
                val t = total(docs)
                PeriodDelta(ref, t.documentCount, t.taxableValue, t.gstValue, t.valuesAbsentCount)
            }

    private fun listing(d: ChangedDocument) =
        DeltaListing(
            d.docUuid,
            d.transactionDate,
            d.precedingDocumentDate,
            d.oldOutcome.code,
            d.newOutcome.code,
            if (d.sgdValuesAbsent) "0.00" else d.taxableValue,
            if (d.sgdValuesAbsent) "0.00" else d.gstValue,
            d.supplyType,
            if (d.sgdValuesAbsent) true else null,
        )

    private data class Sums(
        val documentCount: Int,
        val taxableValue: String,
        val gstValue: String,
        val valuesAbsentCount: Int,
    )

    private fun total(docs: List<ChangedDocument>): Sums {
        val ordinary = docs.filterNot { it.sgdValuesAbsent }
        return Sums(
            docs.size,
            ordinary.fold(ZERO) { a, d -> a + BigDecimal(d.taxableValue) }.toPlainString(),
            ordinary.fold(ZERO) { a, d -> a + BigDecimal(d.gstValue) }.toPlainString(),
            docs.size - ordinary.size,
        )
    }
}

/** Reads the raw members of a page item; nothing is guessed, derived or generated. */
object SampleItems {
    private val ZERO = BigDecimal("0.00")
    private val MONEY = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")

    private const val MAX_PERIOD_REF = 32
    private const val UUID_TEXT_LENGTH = 36

    private class ItemFault(
        message: String,
    ) : RuntimeException(message)

    /** Reads one page item (a raw member map); the position counts from 1. */
    fun read(
        pageNumber: Int,
        position: Int,
        raw: Map<String, Any?>,
    ): ItemReading =
        try {
            ItemReading.Read(readItem(pageNumber, position, raw))
        } catch (fault: ItemFault) {
            ItemReading.Refused(ItemRefusal(pageNumber, position, fault.message.orEmpty()))
        }

    private fun readItem(
        pageNumber: Int,
        position: Int,
        raw: Map<String, Any?>,
    ): SampledDocument {
        val periodRef = raw["periodRef"]
        if (periodRef !is String || periodRef.isBlank() || periodRef.length > MAX_PERIOD_REF) {
            throw ItemFault("periodRef is missing, blank, too long or not a string")
        }
        val docUuid = raw["docUuid"]
        if (docUuid !is String || !isUuidText(docUuid)) throw ItemFault("docUuid is missing or not a UUID")
        val flagged = raw["sgdValuesAbsent"] == true
        return SampledDocument(
            pageNumber = pageNumber,
            position = position,
            docUuid = docUuid,
            periodRef = periodRef,
            entityRef = text(raw, "entityRef"),
            transactionDate = date(raw, "transactionDate"),
            internalTaxCode = text(raw, "internalTaxCode"),
            documentFunction = text(raw, "documentFunction"),
            precedingDocumentDate = raw["precedingDocumentDate"]?.let { date(raw, "precedingDocumentDate") },
            supplyType = (raw["supplyType"] as? String)?.takeIf { it.isNotBlank() },
            sgdValuesAbsent = flagged,
            taxableValue = if (flagged) ZERO else money(raw, "taxableValue"),
            gstValue = if (flagged) ZERO else money(raw, "gstValue"),
        )
    }

    private fun text(
        raw: Map<String, Any?>,
        key: String,
    ): String = (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: throw ItemFault("$key is missing or not a string")

    private fun isUuidText(s: String): Boolean =
        s.length == UUID_TEXT_LENGTH && runCatching { UUID.fromString(s) }.isSuccess &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }

    private fun date(
        raw: Map<String, Any?>,
        key: String,
    ): LocalDate =
        try {
            LocalDate.parse(raw[key] as? String ?: throw ItemFault("$key is missing or not a date"))
        } catch (_: DateTimeParseException) {
            throw ItemFault("$key is not a date")
        }

    private fun money(
        raw: Map<String, Any?>,
        key: String,
    ): BigDecimal {
        val v = raw[key]
        if (v !is String || !MONEY.matches(v)) throw ItemFault("$key is missing or not a decimal")
        return BigDecimal(v).setScale(2)
    }
}
