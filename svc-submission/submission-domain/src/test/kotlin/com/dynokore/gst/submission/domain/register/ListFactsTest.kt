package com.dynokore.gst.submission.domain.register

import com.dynokore.gst.submission.domain.listing.Direction
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ListFactsTest {
    private val names =
        listOf(
            "documentNumber",
            "documentTypeCode",
            "direction",
            "counterpartyName",
            "counterpartyUen",
            "taxableAmount",
            "gstAmount",
            "categoryCodes",
        )

    private fun party(
        name: String,
        scheme: String = "SG_UEN",
    ) = mapOf(
        "name" to name,
        "identifierValue" to "UEN-$name",
        "identifierScheme" to scheme,
        "address" to "CANARY-ADDR-$name",
    )

    @Suppress("LongParameterList")
    private fun payload(
        direction: String? = "SALE",
        currency: String = "SGD",
        number: String? = "INV-1",
        scheme: String = "SG_UEN",
        totals: Map<String, Any?> = mapOf("totalExcludingTax" to "100.00", "totalTax" to "9.00"),
        taxCodes: List<String> = listOf("SR", "SR", "ZR"),
    ): Map<String, Any?> =
        buildMap {
            put(
                "canonicalDocument",
                buildMap {
                    if (number != null) put("documentNumber", mapOf("value" to number))
                    put("documentTypeCode", mapOf("value" to "380"))
                    put("invoiceNote", "CANARY-NOTE")
                    put("currency", mapOf("value" to currency))
                    val both = mapOf("supplier" to party("SUP", scheme), "buyer" to party("BUY", scheme))
                    put("parties", mapOf("value" to both))
                    put("totals", mapOf("value" to totals))
                    val lines =
                        taxCodes.map { mapOf("taxCode" to it, "description" to "CANARY-DESC", "quantity" to "7") }
                    put("lines", mapOf("value" to lines))
                },
            )
            if (direction != null) put("routingFacts", mapOf("direction" to direction))
        }

    private fun complete(r: ListFactsResult): ListFacts = (r as ListFactsResult.Complete).facts

    private fun missing(r: ListFactsResult): Set<String> = (r as ListFactsResult.Incomplete).missing

    @Test
    fun `INV-AC-16320 full SALE payload uses the buyer`() {
        val f = complete(ListFactsPolicy.extract(payload()))
        f.counterpartyName shouldBe "BUY"
        f.counterpartyUen shouldBe "UEN-BUY"
        f.taxableAmount shouldBe BigDecimal("100.00")
        f.gstAmount shouldBe BigDecimal("9.00")
        f.categoryCodes shouldBe setOf("SR", "ZR")
        f.direction shouldBe Direction.SALE
    }

    @Test
    fun `INV-AC-16320 full PURCHASE payload uses the supplier`() {
        complete(ListFactsPolicy.extract(payload(direction = "PURCHASE"))).counterpartyName shouldBe "SUP"
    }

    @Test
    fun `INV-AC-16320 non-SGD currency with SGD totals uses the SGD totals`() {
        val t =
            mapOf(
                "totalExcludingTax" to "1.00",
                "totalTax" to "0.10",
                "sgdTotalExcludingTax" to "1.35",
                "sgdTotalTax" to "0.12",
            )
        val f = complete(ListFactsPolicy.extract(payload(currency = "USD", totals = t)))
        f.taxableAmount shouldBe BigDecimal("1.35")
        f.gstAmount shouldBe BigDecimal("0.12")
    }

    @Test
    fun `INV-AC-16320 non-SGD currency without SGD totals is incomplete`() {
        missing(ListFactsPolicy.extract(payload(currency = "USD"))) shouldBe setOf("taxableAmount", "gstAmount")
    }

    @Test
    fun `INV-AC-16320 missing documentNumber is incomplete`() {
        missing(ListFactsPolicy.extract(payload(number = null))) shouldBe setOf("documentNumber")
        missing(ListFactsPolicy.extract(payload(number = " "))) shouldBe setOf("documentNumber")
    }

    @Test
    fun `INV-AC-16320 missing routingFacts is incomplete`() {
        missing(ListFactsPolicy.extract(payload(direction = null))) shouldBe
            setOf("direction", "counterpartyName", "counterpartyUen")
    }

    @Test
    fun `INV-AC-16320 identifier scheme other than SG_UEN is incomplete`() {
        missing(ListFactsPolicy.extract(payload(scheme = "GST"))) shouldBe setOf("counterpartyUen")
    }

    @Test
    fun `INV-AC-16320 unparsable amount is incomplete`() {
        val t = mapOf("totalExcludingTax" to "abc", "totalTax" to "9.123")
        missing(ListFactsPolicy.extract(payload(totals = t))) shouldBe setOf("taxableAmount", "gstAmount")
    }

    @Test
    fun `INV-AC-16320 only the eight named facts are kept`() {
        ListFacts::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }
            .toSet() shouldBe names.toSet()
        val text = ListFactsPolicy.extract(payload()).toString()
        text shouldNotContain "CANARY"
        text shouldNotContain "ADDR"
    }

    @Test
    fun `INV-AC-16320 FIELDS lists the eight names with classes and masked flags`() {
        val id = ListFactClass.IDENTIFYING
        val none = ListFactClass.NONE
        ListFactsPolicy.FIELDS shouldBe
            listOf(
                ListFactField("documentNumber", none, true),
                ListFactField("documentTypeCode", none, false),
                ListFactField("direction", none, false),
                ListFactField("counterpartyName", id, true),
                ListFactField("counterpartyUen", id, true),
                ListFactField("taxableAmount", none, true),
                ListFactField("gstAmount", none, true),
                ListFactField("categoryCodes", none, true),
            )
    }

    @Test
    fun `INV-AC-16320 mask without the content permission hides content facts`() {
        val f = complete(ListFactsPolicy.extract(payload()))
        ListFactsPolicy.mask(f, false) shouldBe
            MaskedListFacts(null, "380", Direction.SALE, null, null, null, null, emptySet())
        val full = ListFactsPolicy.mask(f, true)
        full shouldBe
            MaskedListFacts(
                f.documentNumber,
                f.documentTypeCode,
                f.direction,
                f.counterpartyName,
                f.counterpartyUen,
                f.taxableAmount,
                f.gstAmount,
                f.categoryCodes,
            )
    }

    @Test
    fun `INV-AC-16320 a 21-character tax code is incomplete for categoryCodes`() {
        missing(ListFactsPolicy.extract(payload(taxCodes = listOf("A".repeat(21))))) shouldBe setOf("categoryCodes")
        complete(ListFactsPolicy.extract(payload(taxCodes = listOf("A".repeat(20))))).categoryCodes.size shouldBe 1
    }

    @Test
    fun `INV-AC-16320 absent or non-list lines is incomplete and an empty list is complete`() {
        missing(ListFactsPolicy.extract(withDoc(payload()) { it - "lines" })) shouldBe setOf("categoryCodes")
        val stringLines = withDoc(payload()) { it + ("lines" to mapOf("value" to "SR")) }
        missing(ListFactsPolicy.extract(stringLines)) shouldBe setOf("categoryCodes")
        complete(ListFactsPolicy.extract(payload(taxCodes = emptyList()))).categoryCodes shouldBe emptySet()
    }

    @Test
    fun `INV-AC-16320 three-decimal amount is incomplete and trailing zeros are stripped`() {
        val bad = mapOf("sgdTotalExcludingTax" to "9.123", "sgdTotalTax" to "1.00")
        missing(ListFactsPolicy.extract(payload(totals = bad))) shouldBe setOf("taxableAmount")
        val ok = mapOf("sgdTotalExcludingTax" to "9.100", "sgdTotalTax" to "1.00")
        complete(ListFactsPolicy.extract(payload(totals = ok))).taxableAmount shouldBe BigDecimal("9.1")
    }

    @Suppress("UNCHECKED_CAST")
    private fun withDoc(
        p: Map<String, Any?>,
        change: (Map<String, Any?>) -> Map<String, Any?>,
    ): Map<String, Any?> = p + ("canonicalDocument" to change(p["canonicalDocument"] as Map<String, Any?>))

    @Suppress("UNCHECKED_CAST")
    private fun moveToTop(
        p: Map<String, Any?>,
        vararg keys: String,
    ): Map<String, Any?> {
        val doc = (p["canonicalDocument"] as Map<String, Any?>).filterKeys { it !in keys }
        return p.filterKeys { it != "canonicalDocument" } +
            mapOf("canonicalDocument" to doc) +
            (p["canonicalDocument"] as Map<String, Any?>).filterKeys { it in keys }
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-20150 a payload with the four members under canonicalDocument and routingFacts at the top level is complete`() {
        val f = complete(ListFactsPolicy.extract(payload()))
        f.counterpartyName shouldBe "BUY"
        f.taxableAmount shouldBe BigDecimal("100.00")
        f.gstAmount shouldBe BigDecimal("9.00")
        f.categoryCodes shouldBe setOf("SR", "ZR")
    }

    @Test
    fun `INV-AC-20150 the four members at the top level only are missing`() {
        val p = moveToTop(payload(), "currency", "parties", "totals", "lines")
        val r = ListFactsPolicy.extract(p)
        (r is ListFactsResult.Incomplete) shouldBe true
        missing(r) shouldBe setOf("counterpartyName", "counterpartyUen", "taxableAmount", "gstAmount", "categoryCodes")
    }

    @Test
    fun `INV-AC-20150 a single member left at the top level is missing on its own`() {
        val r = ListFactsPolicy.extract(moveToTop(payload(), "totals"))
        (r is ListFactsResult.Incomplete) shouldBe true
        missing(r) shouldBe setOf("taxableAmount", "gstAmount")
    }
}
