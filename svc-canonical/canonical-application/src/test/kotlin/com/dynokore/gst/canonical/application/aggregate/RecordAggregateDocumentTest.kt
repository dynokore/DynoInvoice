package com.dynokore.gst.canonical.application.aggregate

import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentAggregateRefs
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.SourceEventStore
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RecordAggregateDocumentTest {
    private val tenant = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val e1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val to = LocalDate.parse("2026-09-30")
    private var nextDoc = 0

    private val documents = mutableListOf<NewDocument>()
    private val staged = mutableListOf<CanonicalVersionCreatedFacts>()
    private val classifyCalls = mutableListOf<String>()
    private val jsonCalls = mutableListOf<List<Any>>()
    private val rows = linkedMapOf<SourceKey, Pair<UUID, String>>()
    private var entityId: UUID? = e1

    private val packs = mutableMapOf<String, Map<String, String>>()
    private val rates = mutableMapOf("SR" to BigDecimal("9.00"), "ZR" to BigDecimal("0.00"))
    private val categories = mutableMapOf("S1" to "SR", "S2" to "SR", "Z1" to "ZR", "S3" to "SR")
    private val noRate = mutableSetOf<String>()
    private val rateOfCode = mutableMapOf<String, BigDecimal>()

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private val sourceStore =
        object : SourceEventStore {
            override fun lockSourceKey(key: SourceKey) = Unit

            override fun docUuidOf(key: SourceKey): UUID? = rows[key]?.first

            override fun boundDocUuid(key: SourceKey): UUID? = null

            override fun resultOf(key: SourceKey): String? = rows[key]?.second

            override fun insert(
                key: SourceKey,
                docUuid: UUID,
                result: String,
            ): Boolean {
                rows[key] = docUuid to result
                return true
            }
        }

    private val store =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DocumentStore::class.java)) { _, method, args ->
            check(method.name == "insert") { "unexpected ${method.name}" }
            documents += args[0] as NewDocument
            null
        } as DocumentStore

    private val writer =
        CanonicalVersionWriter(
            store,
            object : CanonicalVersionCreatedPublisher {
                override fun stage(facts: CanonicalVersionCreatedFacts) {
                    staged += facts
                }
            },
            object : DocumentAggregateRefs {
                override fun aggregateIdOf(docUuid: UUID): String? = null
            },
        )

    private val classifier =
        LineClassifier { _, _, code, _ ->
            classifyCalls += code
            val category = categories[code] ?: return@LineClassifier Refused
            Classified(
                category,
                "IN_SCOPE",
                packs[code] ?: mapOf("SCOPE" to "3", "MAPPING" to "5"),
                if (code in noRate) null else rateOfCode[code] ?: rates[category],
            )
        }

    private val identity = SupplierIdentity("0195:201912345A", "0195:201912345A", "201912345A")
    private var supplierAnswer: SupplierAnswer = SupplierAnswer.Found(identity)

    /** Each call: tenant, entity reference, date and the number of classifier calls made before it. */
    private val supplierCalls = mutableListOf<List<Any>>()
    private val supplier =
        AggregateSupplierIdentity { t, ref, date ->
            supplierCalls += listOf(t, ref, date, classifyCalls.size)
            supplierAnswer
        }

    private val useCase =
        RecordAggregateDocument(
            RecordSourceEvent(sourceStore, tx) { UUID(0, (++nextDoc).toLong()) },
            classifier,
            { _, _ -> entityId },
            supplier,
            { lines, amounts, credit, type, who ->
                jsonCalls += listOf(lines, amounts, credit, type, who)
                "{}"
            },
            writer,
        )

    private fun d(s: String) = BigDecimal(s)

    private fun input(
        type: String = "POS",
        lines: List<AggregateLine> =
            listOf(AggregateLine("S1", d("100.00")), AggregateLine("S2", d("50.25")), AggregateLine("Z1", d("10.00"))),
    ) = AggregateInput(tenant, "AGG-2026-0001", "ENT-1", type, "2026Q3", to, false, lines)

    private fun amounts() = jsonCalls.last()[1] as AggregateAmounts

    private fun refusal(i: AggregateInput) = assertThrows<AggregateRefused> { useCase.record(i) }.code

    private fun nothingWritten() {
        documents.size shouldBe 0
        staged.size shouldBe 0
    }

    @Test
    @DisplayName(
        "INV-AC-22850 first aggregate writes one SALE document 380 with lines SR 150.25 and ZR 10.00, " +
            "tax 13.52 and 0.00 and totals 160.25, 13.52 and 173.77",
    )
    fun `INV-AC-22850 first aggregate writes one SALE document 380`() {
        val result = useCase.record(input())
        documents.size shouldBe 1
        val doc = documents.single()
        assertDocument(doc)
        @Suppress("UNCHECKED_CAST")
        val lines = jsonCalls.single()[0] as List<MergedLine>
        lines.map { it.categoryCode to it.amount.toPlainString() } shouldBe listOf("SR" to "150.25", "ZR" to "10.00")
        jsonCalls.single()[3] shouldBe "POS"
        val a = amounts()
        a.subtotals.map {
            listOf(
                it.categoryCode,
                it.taxableAmount.toPlainString(),
                it.taxAmount.toPlainString(),
            )
        } shouldBe
            listOf(listOf("SR", "150.25", "13.52"), listOf("ZR", "10.00", "0.00"))
        a.totalExcludingTax.toPlainString() shouldBe "160.25"
        a.totalTax.toPlainString() shouldBe "13.52"
        a.totalIncludingTax.toPlainString() shouldBe "173.77"
        assertFacts(doc)
        result.inserted shouldBe true
    }

    private fun assertDocument(doc: NewDocument) {
        doc.direction shouldBe Direction.SALE
        doc.documentTypeCode shouldBe 380.toShort()
        doc.documentNumber shouldBe "AGG-2026-0001"
        doc.issuerPartyKey shouldBe "ENT-1"
        doc.fiscalYear shouldBe 2026.toShort()
        doc.entityId shouldBe e1
        doc.internalReference shouldBe null
        doc.businessUnitId shouldBe null
        doc.state shouldBe DocumentState.READY
        doc.first.scopeVerdict shouldBe ScopeVerdict.IN_SCOPE
        doc.first.scopePackVersion shouldBe "3"
        doc.first.mappingPackVersion shouldBe "5"
        doc.first.mdePackVersion shouldBe null
        doc.first.specificationVersion shouldBe null
        doc.first.transactionDate shouldBe to
        doc.first.createdBy shouldBe "system:aggregate-ready"
    }

    private fun assertFacts(doc: NewDocument) {
        val f = staged.single()
        f.aggregateId shouldBe "AGG-2026-0001"
        f.traceReference shouldBe "${doc.docUuid}:1"
        f.prescribedPeriodRef shouldBe "2026Q3"
        f.routingFacts.direction shouldBe RoutingDirection.SALE
        f.routingFacts.networkPath shouldBe RoutingNetworkPath.NOT_ON_NETWORK
        f.routingFacts.counterpartyOnNetwork shouldBe null
        f.routingFacts.debitNote shouldBe null
        f.routingFacts.selfBillingAssignment shouldBe null
    }

    @Test
    fun `INV-AC-22850 repeat writes nothing`() {
        val first = useCase.record(input())
        val again = useCase.record(input())
        documents.size shouldBe 1
        again.inserted shouldBe false
        again.result shouldBe first.result
    }

    @Test
    fun `INV-AC-22850 credit note aggregate gets 381`() {
        useCase.record(input().copy(aggregateId = "AGG-CN", isCreditNote = true))
        documents.single().documentTypeCode shouldBe 381.toShort()
    }

    @Test
    fun `INV-AC-22850 unmapped tax code is refused`() {
        refusal(input(lines = listOf(AggregateLine("X9", d("1.00"))))) shouldBe "UNMAPPED_TAX_CODE"
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 entity without id retries`() {
        entityId = null
        assertThrows<AggregateRetry> { useCase.record(input()) }
        nothingWritten()
        rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-22850 pack version mismatch is refused`() {
        packs["S2"] = mapOf("SCOPE" to "4", "MAPPING" to "5")
        refusal(input()) shouldBe "PACK_VERSION_MISMATCH"
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 missing window end or period is refused`() {
        refusal(input().copy(windowTo = null)) shouldBe "WINDOW_INCOMPLETE"
        refusal(input().copy(periodRef = null)) shouldBe "WINDOW_INCOMPLETE"
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 fiscal year is the year of window end`() {
        useCase.record(input().copy(windowTo = LocalDate.parse("2027-01-31")))
        documents.single().fiscalYear shouldBe 2027.toShort()
    }

    @Test
    fun `INV-AC-22850 STI aggregate is a SALE document`() {
        useCase.record(input(type = "STI"))
        documents.single().direction shouldBe Direction.SALE
        jsonCalls.single()[3] shouldBe "STI"
    }

    @Test
    fun `INV-AC-22850 direction of each aggregate type`() {
        AggregateDirection.of("POS") shouldBe Direction.SALE
        AggregateDirection.of("STI") shouldBe Direction.SALE
        AggregateDirection.of("PCP") shouldBe Direction.PURCHASE
        assertThrows<IllegalArgumentException> { AggregateDirection.of("XYZ") }
    }

    private fun pcp() =
        input(
            type = "PCP",
            lines =
                listOf(
                    AggregateLine("S1", d("9.00"), d("100.00")),
                    AggregateLine("S2", d("4.00"), d("50.00")),
                    AggregateLine("Z1", d("0.00"), d("20.00")),
                ),
        )

    @Test
    @DisplayName(
        "INV-AC-22850 PCP aggregate states tax as the sum of its line amounts and " +
            "taxable amount as the sum of its taxable amounts",
    )
    fun `INV-AC-22850 PCP aggregate states tax and taxable amount as sums`() {
        useCase.record(pcp())
        val doc = documents.single()
        doc.direction shouldBe Direction.PURCHASE
        staged.single().routingFacts.direction shouldBe RoutingDirection.PURCHASE
        val a = amounts()
        a.subtotals.map {
            listOf(
                it.categoryCode,
                it.taxableAmount.toPlainString(),
                it.taxAmount.toPlainString(),
            )
        } shouldBe
            listOf(listOf("SR", "150.00", "13.00"), listOf("ZR", "20.00", "0.00"))
        a.totalExcludingTax.toPlainString() shouldBe "170.00"
        a.totalTax.toPlainString() shouldBe "13.00"
        a.totalIncludingTax.toPlainString() shouldBe "183.00"
    }

    @Test
    fun `INV-AC-22850 PCP aggregate reads no rate`() {
        noRate += listOf("S1", "S2", "Z1")
        useCase.record(pcp())
        @Suppress("UNCHECKED_CAST")
        (jsonCalls.single()[0] as List<MergedLine>).all { it.rate == null } shouldBe true
    }

    @Test
    fun `INV-AC-22850 PCP line without taxable amount is refused before any rules call`() {
        val lines = listOf(AggregateLine("S1", d("9.00"), d("100.00")), AggregateLine("S2", d("4.00")))
        refusal(input(type = "PCP", lines = lines)) shouldBe "PCP_TAXABLE_AMOUNT_MISSING"
        classifyCalls.size shouldBe 0
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 PCP taxable amount with three decimals is refused`() {
        refusal(input(type = "PCP", lines = listOf(AggregateLine("S1", d("9.00"), d("100.005"))))) shouldBe
            "AMOUNT_SCALE"
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 tax is taxable amount times rate`() {
        useCase.record(input(lines = listOf(AggregateLine("S1", d("200.00")))))
        amounts()
            .subtotals
            .single()
            .taxAmount
            .toPlainString() shouldBe "18.00"
    }

    @Test
    fun `INV-AC-22850 tax rounds half up`() {
        useCase.record(input(lines = listOf(AggregateLine("S1", d("0.50")))))
        amounts()
            .subtotals
            .single()
            .taxAmount
            .toPlainString() shouldBe "0.05"
    }

    @Test
    fun `INV-AC-22850 missing rate is refused`() {
        noRate += "S1"
        refusal(input()) shouldBe "RATE_MISSING"
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 different rates for one category code are refused`() {
        rateOfCode["S2"] = d("8.00")
        refusal(input()) shouldBe "RATE_MISMATCH"
        nothingWritten()
    }

    @Test
    fun `INV-AC-22850 amount with three decimals is refused`() {
        refusal(input(lines = listOf(AggregateLine("S1", d("1.005"))))) shouldBe "AMOUNT_SCALE"
        nothingWritten()
    }

    @Test
    @DisplayName(
        "INV-AC-23774 supplier identity is read once at the window end after the classification " +
            "and passed unchanged to the builder",
    )
    fun `INV-AC-23774 supplier identity is read once and passed unchanged`() {
        useCase.record(input(lines = listOf(AggregateLine("S1", d("100.00")), AggregateLine("Z1", d("10.00")))))
        supplierCalls shouldBe listOf(listOf(tenant, "ENT-1", to, 2))
        jsonCalls.size shouldBe 1
        jsonCalls.single()[4] shouldBe identity
        documents.size shouldBe 1
    }

    @Test
    fun `INV-AC-23774 pending supplier identity retries and writes nothing`() {
        supplierAnswer = SupplierAnswer.Retry("constitution unknown")
        val e = assertThrows<AggregateRetry> { useCase.record(input()) }
        e.message.orEmpty().contains("constitution unknown") shouldBe true
        supplierCalls.size shouldBe 1
        jsonCalls.size shouldBe 0
        nothingWritten()
    }

    @Test
    fun `INV-AC-23774 refused aggregate does not read the supplier identity`() {
        refusal(input(lines = listOf(AggregateLine("X9", d("5.00"))))) shouldBe "UNMAPPED_TAX_CODE"
        supplierCalls.size shouldBe 0
        jsonCalls.size shouldBe 0
    }
}
