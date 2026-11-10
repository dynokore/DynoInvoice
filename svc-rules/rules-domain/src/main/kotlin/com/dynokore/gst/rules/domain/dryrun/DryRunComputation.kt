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
    private val MONEY = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")

    /** Reads one page item (a raw member map); the position counts from 1. */
    fun read(
        pageNumber: Int,
        position: Int,
        raw: Map<String, Any?>,
    ): ItemReading {
        fun refuse(reason: String) = ItemReading.Refused(ItemRefusal(pageNumber, position, reason))

        val periodRef = raw["periodRef"]
        if (periodRef !is String || periodRef.isBlank() || periodRef.length > 32) {
            return refuse("periodRef is missing, blank, too long or not a string")
        }
        val docUuid = raw["docUuid"]
        if (docUuid !is String || !isUuidText(docUuid)) return refuse("docUuid is missing or not a UUID")
        val entityRef = text(raw["entityRef"]) ?: return refuse("entityRef is missing or not a string")
        val taxCode = text(raw["internalTaxCode"]) ?: return refuse("internalTaxCode is missing or not a string")
        val function = text(raw["documentFunction"]) ?: return refuse("documentFunction is missing or not a string")
        val date = date(raw["transactionDate"]) ?: return refuse("transactionDate is missing or not a date")
        val preceding =
            if (raw["precedingDocumentDate"] == null) {
                null
            } else {
                date(raw["precedingDocumentDate"])
                    ?: return refuse("precedingDocumentDate is not a date")
            }
        val flagged = raw["sgdValuesAbsent"] == true
        var taxable = ZERO
        var gst = ZERO
        if (!flagged) {
            taxable = money(raw["taxableValue"]) ?: return refuse("taxableValue is missing or not a decimal")
            gst = money(raw["gstValue"]) ?: return refuse("gstValue is missing or not a decimal")
        }
        return ItemReading.Read(
            SampledDocument(
                pageNumber,
                position,
                docUuid,
                periodRef,
                entityRef,
                date,
                taxCode,
                function,
                preceding,
                raw["supplyType"] as? String,
                flagged,
                taxable,
                gst,
            ),
        )
    }

    private fun text(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }

    private fun isUuidText(s: String): Boolean =
        s.length == 36 && runCatching { UUID.fromString(s) }.isSuccess &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }

    private fun date(v: Any?): LocalDate? =
        if (v !is String) {
            null
        } else {
            try {
                LocalDate.parse(v)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun money(v: Any?): BigDecimal? = if (v is String && MONEY.matches(v)) BigDecimal(v).setScale(2) else null

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

    /**
     * A credit note whose own date and preceding date lie on different sides of [effectiveFrom] (before against on or after).
     */
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
        val changed =
            outcomes.mapNotNull { (d, old, new) ->
                val type = changeTypeOf(old, new) ?: return@mapNotNull null
                ChangedDocument(
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
        val (straddling, general) = changed.partition { straddles(it, effectiveFrom) }
        return DryRunDelta(
            changedCount = changed.size,
            documents = changed,
            changeTypeTotals =
                ChangeType.entries.map { t ->
                    total(
                        changed.filter {
                            it.changeType == t
                        },
                    ).let {
                        ChangeTypeTotal(
                            t,
                            it.documentCount,
                            it.taxableValue,
                            it.gstValue,
                            it.valuesAbsentCount,
                        )
                    }
                },
            byPeriod =
                changed
                    .groupBy { it.periodRef }
                    .map { (ref, docs) -> ref to docs }
                    .sortedWith(compareBy({ (_, docs) -> docs.minOf { it.transactionDate } }, { it.first }))
                    .map { (ref, docs) ->
                        val t = total(docs)
                        PeriodDelta(ref, t.documentCount, t.taxableValue, t.gstValue, t.valuesAbsentCount)
                    },
            straddlingCreditNotes = straddling.map(::listing),
            sample = general.map(::listing),
        )
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
