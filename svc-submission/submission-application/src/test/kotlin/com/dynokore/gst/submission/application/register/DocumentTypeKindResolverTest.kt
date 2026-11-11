package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.metrics.RowKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The kind resolver over the document type code of the list facts (INV-AC-20513). */
class DocumentTypeKindResolverTest {
    private val resolver = DocumentTypeKindResolver()

    private fun party(name: String) =
        mapOf("identifierScheme" to "SG_UEN", "identifierValue" to "201912345A", "name" to name)

    private fun data(code: String) =
        CanonicalVersionCreatedData(
            "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            1,
            LocalDate.of(2026, 3, 20),
            "2026-Q1",
            "SUBMITTABLE",
            mapOf(
                "canonicalDocument" to
                    mapOf(
                        "documentNumber" to mapOf("value" to "INV-1"),
                        "documentTypeCode" to mapOf("value" to code),
                        "currency" to mapOf("value" to "SGD"),
                        "parties" to mapOf("value" to mapOf("supplier" to party("SUP"), "buyer" to party("BUY"))),
                        "totals" to mapOf("value" to mapOf("totalExcludingTax" to "100.00", "totalTax" to "9.00")),
                        "lines" to mapOf("value" to listOf(mapOf("taxCode" to "SR"))),
                    ),
                "routingFacts" to mapOf("direction" to "SALE"),
            ),
        )

    @Test
    fun `INV-AC-20513 code 380 gives INVOICE`() {
        resolver.kindOf(data("380")) shouldBe RowKind.INVOICE
    }

    @Test
    fun `INV-AC-20513 code 381 gives CREDIT_NOTE`() {
        resolver.kindOf(data("381")) shouldBe RowKind.CREDIT_NOTE
    }

    @Test
    fun `INV-AC-20513 code 999 gives null`() {
        resolver.kindOf(data("999")) shouldBe null
    }

    @Test
    fun `INV-AC-20513 the look-alike codes 38 and 3800 give null`() {
        resolver.kindOf(data("38")) shouldBe null
        resolver.kindOf(data("3800")) shouldBe null
    }

    @Test
    fun `INV-AC-20513 incomplete list facts give null`() {
        resolver.kindOf(
            data("380").let {
                CanonicalVersionCreatedData(it.docUuid, 1, it.transactionDate, "p", "s", emptyMap())
            },
        ) shouldBe
            null
    }
}
