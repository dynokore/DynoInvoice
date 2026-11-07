package com.dynokore.gst.submission.domain.register

import com.dynokore.gst.submission.domain.listing.Direction
import java.math.BigDecimal

/** The named list facts of one document version; nothing else of the invoice is kept. */
data class ListFacts(
    val documentNumber: String,
    val documentTypeCode: String,
    val direction: Direction,
    val counterpartyName: String,
    val counterpartyUen: String,
    val taxableAmount: BigDecimal,
    val gstAmount: BigDecimal,
    val categoryCodes: Set<String>,
)

/** The list facts as served to a caller; a masked member is absent. */
data class MaskedListFacts(
    val documentNumber: String?,
    val documentTypeCode: String,
    val direction: Direction,
    val counterpartyName: String?,
    val counterpartyUen: String?,
    val taxableAmount: BigDecimal?,
    val gstAmount: BigDecimal?,
    val categoryCodes: Set<String>,
)

enum class ListFactClass { NONE, IDENTIFYING }

data class ListFactField(
    val name: String,
    val sourceClass: ListFactClass,
    val masked: Boolean,
)

sealed interface ListFactsResult {
    data class Complete(
        val facts: ListFacts,
    ) : ListFactsResult

    data class Incomplete(
        val missing: Set<String>,
    ) : ListFactsResult
}

object ListFactsPolicy {
    private const val MAX_CODE_LENGTH = 20
    private const val MAX_SCALE = 2
    private const val SGD = "SGD"
    private const val SG_UEN = "SG_UEN"

    val FIELDS: List<ListFactField> =
        listOf(
            ListFactField("documentNumber", ListFactClass.NONE, true),
            ListFactField("documentTypeCode", ListFactClass.NONE, false),
            ListFactField("direction", ListFactClass.NONE, false),
            ListFactField("counterpartyName", ListFactClass.IDENTIFYING, true),
            ListFactField("counterpartyUen", ListFactClass.IDENTIFYING, true),
            ListFactField("taxableAmount", ListFactClass.NONE, true),
            ListFactField("gstAmount", ListFactClass.NONE, true),
            ListFactField("categoryCodes", ListFactClass.NONE, true),
        )

    fun extract(payload: Map<String, Any?>): ListFactsResult {
        val doc = payload.map("canonicalDocument")
        val name = payload.map("routingFacts").text("direction")
        val direction = Direction.entries.firstOrNull { it.name == name }
        val party = counterparty(payload.map("parties").map("value"), direction)
        val sgd = payload.map("currency").text("value") == SGD
        val totals = payload.map("totals").map("value")
        val uen = party.text("identifierValue")?.takeIf { party.text("identifierScheme") == SG_UEN }
        val missing = mutableSetOf<String>()

        fun <T : Any> need(
            name: String,
            value: T?,
            placeholder: T,
        ): T = value ?: placeholder.also { missing.add(name) }

        val facts =
            ListFacts(
                documentNumber = need("documentNumber", doc.map("documentNumber").filled("value"), ""),
                documentTypeCode = need("documentTypeCode", doc.map("documentTypeCode").filled("value"), ""),
                direction = need("direction", direction, Direction.SALE),
                counterpartyName = need("counterpartyName", party.filled("name"), ""),
                counterpartyUen = need("counterpartyUen", uen?.takeIf { it.isNotBlank() }, ""),
                taxableAmount =
                    need(
                        "taxableAmount",
                        amount(totals, "sgdTotalExcludingTax", "totalExcludingTax", sgd),
                        BigDecimal.ZERO,
                    ),
                gstAmount = need("gstAmount", amount(totals, "sgdTotalTax", "totalTax", sgd), BigDecimal.ZERO),
                categoryCodes = need("categoryCodes", categories(payload.map("lines")["value"]), emptySet()),
            )
        return if (missing.isEmpty()) ListFactsResult.Complete(facts) else ListFactsResult.Incomplete(missing)
    }

    private fun counterparty(
        parties: Map<String, Any?>,
        direction: Direction?,
    ): Map<String, Any?> =
        when (direction) {
            Direction.SALE -> parties.map("buyer")
            Direction.PURCHASE -> parties.map("supplier")
            null -> emptyMap()
        }

    private fun Map<String, Any?>.filled(key: String): String? = text(key)?.takeIf { it.isNotBlank() }

    fun mask(
        facts: ListFacts,
        mayViewContent: Boolean,
    ): MaskedListFacts {
        val masked = FIELDS.associate { it.name to (!mayViewContent && it.masked) }

        fun hide(name: String): Boolean =
            masked[name] ?: throw IllegalArgumentException("unclassified list fact: $name")
        return MaskedListFacts(
            documentNumber = facts.documentNumber.takeUnless { hide("documentNumber") },
            documentTypeCode = facts.documentTypeCode.also { hide("documentTypeCode") },
            direction = facts.direction.also { hide("direction") },
            counterpartyName = facts.counterpartyName.takeUnless { hide("counterpartyName") },
            counterpartyUen = facts.counterpartyUen.takeUnless { hide("counterpartyUen") },
            taxableAmount = facts.taxableAmount.takeUnless { hide("taxableAmount") },
            gstAmount = facts.gstAmount.takeUnless { hide("gstAmount") },
            categoryCodes = if (hide("categoryCodes")) emptySet() else facts.categoryCodes,
        )
    }

    private fun amount(
        totals: Map<String, Any?>,
        sgdKey: String,
        plainKey: String,
        sgdCurrency: Boolean,
    ): BigDecimal? {
        val raw = totals[sgdKey] ?: if (sgdCurrency) totals[plainKey] else null
        return parse(raw)
    }

    private fun parse(raw: Any?): BigDecimal? {
        val value = (raw as? String)?.trim()?.toBigDecimalOrNull()
        val reduced = if (value != null && value.scale() > MAX_SCALE) value.stripTrailingZeros() else value
        return reduced?.takeIf { it.scale() <= MAX_SCALE }?.let { if (it.scale() < 0) it.setScale(0) else it }
    }

    private fun categories(lines: Any?): Set<String>? {
        val list = lines as? List<*> ?: return null
        val codes =
            list
                .mapNotNull { (it as? Map<*, *>)?.get("taxCode") as? String }
                .filter { it.isNotBlank() }
                .toSet()
        return if (codes.any { it.length > MAX_CODE_LENGTH }) null else codes
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = (this[key] as? Map<String, Any?>).orEmpty()

    private fun Map<String, Any?>.text(key: String): String? = this[key] as? String
}
