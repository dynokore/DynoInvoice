package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqSampleDocumentReader
import com.dynokore.gst.canonical.application.sample.AnswerSampleRequest
import com.dynokore.gst.canonical.application.sample.SampleDocumentFunctions
import com.dynokore.gst.canonical.application.sample.SampleDocumentWithoutSubtotalException
import com.dynokore.gst.canonical.application.sample.SamplePage
import com.dynokore.gst.canonical.application.sample.SamplePagePublisher
import com.dynokore.gst.canonical.application.sample.SamplePeriodCalendar
import com.dynokore.gst.canonical.application.sample.SamplePeriods
import com.dynokore.gst.canonical.application.sample.SampleRequest
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** The fixtures and the plumbing the two sample test classes share. */
internal object SampleKit {
    val march = PeriodSpan("2026-03", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
    val april = PeriodSpan("2026-04", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
    private val mapper = JsonMapper.builder().build()

    class Sub(
        val code: String,
        val taxable: String,
        val tax: String,
    )

    @Suppress("LongParameterList") // a fixture builder: every argument has a default
    fun canonical(
        subtotals: List<Sub>,
        currency: String = "SGD",
        sgdExcluding: String? = null,
        sgdTax: String? = null,
        sgdIncluding: String? = null,
        exchangeRate: String? = null,
        preceding: String? = null,
    ): String {
        val lines =
            subtotals.withIndex().joinToString(",") { (i, s) ->
                """{"lineNumber":${i + 1},"description":"item","quantity":1,"unitPrice":"${s.taxable}",""" +
                    """"lineAmount":"${s.taxable}","taxCode":"${s.code}","taxAmount":"${s.tax}"}"""
            }
        val subs =
            subtotals.joinToString(",") {
                """{"taxCode":"${it.code}","taxableAmount":"${it.taxable}","taxAmount":"${it.tax}"}"""
            }
        val totals =
            listOfNotNull(
                """"totalExcludingTax":"999.00"""",
                sgdExcluding?.let { """"sgdTotalExcludingTax":"$it"""" },
                sgdTax?.let { """"sgdTotalTax":"$it"""" },
                sgdIncluding?.let { """"sgdTotalIncludingTax":"$it"""" },
                exchangeRate?.let { """"exchangeRate":"$it"""" },
            ).joinToString(",")
        val before = preceding?.let { """"precedingDocumentDate":{"value":"$it","classification":"NONE"},""" }.orEmpty()
        return """{"currency":{"value":"$currency","classification":"NONE"},$before""" +
            """"totals":{"value":{$totals},"classification":"NONE"},""" +
            """"parties":{"value":{"supplier":{},"buyer":{}},"classification":"content"},""" +
            """"lines":{"value":[$lines],"classification":"content"},""" +
            """"taxSubtotals":{"value":[$subs],"classification":"content"}}"""
    }

    fun withType(
        document: NewDocument,
        code: Int,
    ) = document.copy(documentTypeCode = code.toShort())

    private fun contractDir() =
        File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })

    /** The payload schema of the pinned bundle; it sits beside the canonical contract in the staged repository. */
    fun schemaFile(): File =
        File(contractDir().parentFile, "service-runtime/schemas/pack-dry-run-sample-recorded-payload.schema.json")

    val schemaTree: JsonNode by lazy { mapper.readTree(schemaFile().readText()) }

    /** `properties.documents.maxItems` of the schema. */
    val maxItems: Int by lazy {
        schemaTree
            .path("properties")
            .path("documents")
            .path("maxItems")
            .asInt()
    }

    private val registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    /** The page as the publisher writes it, as JSON. */
    fun json(page: SamplePage): JsonNode = mapper.readTree(mapper.writeValueAsString(page.payload()))

    fun errors(page: SamplePage): List<String> =
        registry.getSchema(schemaFile().readText()).validate(json(page)).map { it.toString() }
}

internal class RecordingPublisher : SamplePagePublisher {
    val pages = mutableListOf<SamplePage>()

    override fun publish(
        tenantId: UUID,
        page: SamplePage,
    ) {
        pages += page
    }
}

/** The sample read over PostgreSQL through [JooqSampleDocumentReader] (INV-REQ-772 b1). */
class SampleDocumentReaderTest {
    private val kit = DocumentKit()
    private val reader = JooqSampleDocumentReader(kit.dsl)
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val march = SampleKit.march
    private val april = SampleKit.april
    private val functions =
        SampleDocumentFunctions {
            mapOf(
                380 to UpstreamFunction.BILL_FOR_PAYMENT,
                381 to UpstreamFunction.ADJUSTMENT,
                751 to UpstreamFunction.NOT_A_BILL,
            )[it]
        }

    private fun request(
        owner: UUID = tenant,
        of: UUID = entity,
    ) = SampleRequest(owner, "dry-1", "ENT-A", of, "2026-03", "2026-04")

    private fun insert(
        on: LocalDate,
        canonical: String,
        owner: UUID = tenant,
        within: UUID = entity,
        code: Int = 380,
    ): NewDocument =
        SampleKit.withType(DocumentKit.document(owner, within, on, canonical = canonical), code).also { kit.insert(it) }

    private fun answer(request: SampleRequest = request()): List<SamplePage> {
        val publisher = RecordingPublisher()
        val calendar = SamplePeriodCalendar { _, _, _, _ -> SamplePeriods.Known(listOf(march, april)) }
        kit.asTenant(request.tenantId) {
            kit.transaction.inTransaction {
                AnswerSampleRequest(reader, publisher, functions, calendar, SampleKit.maxItems).answer(request)
            }
        }
        return publisher.pages
    }

    private fun items(request: SampleRequest = request()): Map<String, JsonNode> =
        answer(request)
            .flatMap { page -> SampleKit.json(page).path("documents").toList() }
            .associateBy { it.path("docUuid").asString() }

    private val two = listOf(SampleKit.Sub("SR", "100.00", "9.00"), SampleKit.Sub("ZR", "10.00", "0.00"))

    @Test
    fun `INV-AC-19916 an SGD document with two tax codes carries the sums of its subtotal rows`() {
        val document = insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two))
        val item = items().getValue(document.docUuid.toString())
        item.path("taxableValue").asString() shouldBe "110.00"
        item.path("gstValue").asString() shouldBe "9.00"
        item.has("sgdValuesAbsent") shouldBe false
    }

    @Test
    fun `INV-AC-19916 a USD document with stated SGD totals carries the stated totals and not the subtotal sums`() {
        val document =
            insert(
                LocalDate.of(2026, 3, 10),
                SampleKit.canonical(two, "USD", "150.25", "13.52", "163.77", "1.3500"),
            )
        val item = items().getValue(document.docUuid.toString())
        item.path("taxableValue").asString() shouldBe "150.25"
        item.path("gstValue").asString() shouldBe "13.52"
        item.has("sgdValuesAbsent") shouldBe false
    }

    @Test
    fun `INV-AC-19916 a USD document with a missing SGD total is flagged with placeholders despite its subtotals`() {
        val none = insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two, "USD"))
        val half = insert(LocalDate.of(2026, 3, 11), SampleKit.canonical(two, "USD", sgdExcluding = "150.00"))
        val other = insert(LocalDate.of(2026, 3, 12), SampleKit.canonical(two, "USD", sgdTax = "13.50"))
        val read = items()
        listOf(none, half, other).forEach {
            val item = read.getValue(it.docUuid.toString())
            item.path("sgdValuesAbsent").asBoolean() shouldBe true
            item.path("taxableValue").asString() shouldBe "0.00"
            item.path("gstValue").asString() shouldBe "0.00"
        }
    }

    @Test
    fun `INV-AC-19916 a credit note carries the preceding date of its stored version`() {
        val credit =
            insert(
                LocalDate.of(2026, 4, 2),
                SampleKit.canonical(listOf(SampleKit.Sub("SR", "-50.00", "-4.50")), preceding = "2026-03-02"),
                code = 381,
            )
        val item = items().getValue(credit.docUuid.toString())
        item.path("precedingDocumentDate").asString() shouldBe "2026-03-02"
        item.path("documentFunction").asString() shouldBe "ADJUSTMENT"
        item.path("taxableValue").asString() shouldBe "-50.00"
        item.path("gstValue").asString() shouldBe "-4.50"
    }

    @Test
    fun `INV-AC-19916 documents of another entity, tenant or period never appear`() {
        val own = insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two))
        insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two), within = UUID.randomUUID())
        val otherTenant = UUID.randomUUID()
        val foreign =
            insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two), owner = otherTenant, within = UUID.randomUUID())
        insert(LocalDate.of(2026, 5, 1), SampleKit.canonical(two))
        insert(LocalDate.of(2026, 2, 28), SampleKit.canonical(two))
        items().keys shouldBe setOf(own.docUuid.toString())
        items(request(otherTenant, entity)).keys.shouldBeEmpty()
        items(request(tenant, foreign.entityId)).keys.shouldBeEmpty()
        items(request(otherTenant, foreign.entityId)).keys shouldBe setOf(foreign.docUuid.toString())
    }

    @Test
    fun `INV-AC-19916 a window of one period returns none of the neighbouring period`() {
        insert(LocalDate.of(2026, 3, 31), SampleKit.canonical(two))
        val inApril = insert(LocalDate.of(2026, 4, 1), SampleKit.canonical(two))
        val window = april.start to april.end
        kit.asTenant(tenant) {
            kit.transaction.inTransaction {
                reader.read(tenant, entity, window.first, window.second, 10, null).map { it.docUuid }
            }
        } shouldBe listOf(inApril.docUuid)
    }

    @Test
    fun `INV-AC-19916 the distinct type codes are those of the entity in the window only`() {
        insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two), code = 380)
        insert(LocalDate.of(2026, 4, 10), SampleKit.canonical(two), code = 381)
        insert(LocalDate.of(2026, 5, 10), SampleKit.canonical(two), code = 751)
        insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two), within = UUID.randomUUID(), code = 82)
        insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two), owner = UUID.randomUUID(), code = 83)
        kit.asTenant(tenant) {
            kit.transaction.inTransaction { reader.distinctTypeCodes(tenant, entity, march.start, april.end) }
        } shouldBe setOf(380, 381)
    }

    @Test
    fun `INV-AC-19916 multi-code document carries the code of the largest absolute taxable amount`() {
        val a = insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(two))
        val b =
            insert(
                LocalDate.of(2026, 3, 11),
                SampleKit.canonical(
                    listOf(SampleKit.Sub("SR", "-100.00", "-9.00"), SampleKit.Sub("ZR", "60.00", "0.00")),
                ),
            )
        val read = items()
        read.getValue(a.docUuid.toString()).path("internalTaxCode").asString() shouldBe "SR"
        read.getValue(b.docUuid.toString()).path("internalTaxCode").asString() shouldBe "SR"
    }

    @Test
    fun `INV-AC-19916 equal absolute amounts go to the lowest code in binary order`() {
        val tie = listOf(SampleKit.Sub("TX7", "50.00", "0.00"), SampleKit.Sub("TX-7", "-50.00", "0.00"))
        val document = insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(tie))
        items().getValue(document.docUuid.toString()).path("internalTaxCode").asString() shouldBe "TX-7"
    }

    @Test
    fun `INV-AC-19916 one item per document`() {
        val three =
            listOf(
                SampleKit.Sub("A", "1.00", "0.00"),
                SampleKit.Sub("B", "2.00", "0.00"),
                SampleKit.Sub("C", "3.00", "0.00"),
            )
        insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(three))
        val pages = answer()
        pages.size shouldBe 1
        pages.single().documents.size shouldBe 1
    }

    @Test
    fun `INV-AC-19916 a single-code document carries that code and an old version row is never used`() {
        val sr = SampleKit.canonical(listOf(SampleKit.Sub("SR", "5.00", "0.45")))
        val document = insert(LocalDate.of(2026, 3, 10), sr)
        kit.asTenant(tenant) {
            kit.versions.createVersion(
                document.docUuid,
                1,
                DocumentKit.version(
                    LocalDate.of(2026, 3, 10),
                    SampleKit.canonical(listOf(SampleKit.Sub("ZR", "7.00", "0.00"))),
                ),
            )
        }
        val item = items().getValue(document.docUuid.toString())
        item.path("internalTaxCode").asString() shouldBe "ZR"
        item.path("taxableValue").asString() shouldBe "7.00"
    }

    @Test
    fun `INV-AC-19916 document without subtotal row fails the request and no page holds it`() {
        val bare = insert(LocalDate.of(2026, 3, 10), SampleKit.canonical(emptyList()))
        val publisher = RecordingPublisher()
        val calendar = SamplePeriodCalendar { _, _, _, _ -> SamplePeriods.Known(listOf(march, april)) }
        val failure =
            shouldThrow<SampleDocumentWithoutSubtotalException> {
                kit.asTenant(tenant) {
                    kit.transaction.inTransaction {
                        val use = AnswerSampleRequest(reader, publisher, functions, calendar, SampleKit.maxItems)
                        use.answer(request())
                    }
                }
            }
        failure.docUuid shouldBe bare.docUuid
        publisher.pages.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19916 each item carries the stored doc uuid and the period of its own date, boundary days included`() {
        val lastOfMarch = insert(march.end, SampleKit.canonical(two))
        val firstOfApril = insert(april.start, SampleKit.canonical(two))
        val credit = insert(LocalDate.of(2026, 4, 9), SampleKit.canonical(two, preceding = "2026-03-01"), code = 381)
        val flagged = insert(LocalDate.of(2026, 3, 9), SampleKit.canonical(two, "USD"))
        val read = items()
        read.getValue(lastOfMarch.docUuid.toString()).path("periodRef").asString() shouldBe "2026-03"
        read.getValue(firstOfApril.docUuid.toString()).path("periodRef").asString() shouldBe "2026-04"
        read.getValue(credit.docUuid.toString()).path("periodRef").asString() shouldBe "2026-04"
        read.getValue(flagged.docUuid.toString()).path("periodRef").asString() shouldBe "2026-03"
        read.keys shouldBe setOf(lastOfMarch, firstOfApril, credit, flagged).map { it.docUuid.toString() }.toSet()
        answer().forEach { SampleKit.errors(it).shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-19916 the enum of documentFunction in the schema equals the function names`() {
        val names =
            SampleKit.schemaTree
                .path("properties")
                .path("documents")
                .path("items")
                .path("properties")
                .path("documentFunction")
                .path("enum")
                .toList()
                .map { it.asString() }
                .toSet()
        names shouldBe UpstreamFunction.entries.map { it.name }.toSet()
    }

    /** The columns at the branch point: those of INV-T-5544 to INV-T-5546 and of the later tasks; this task adds none. */
    private val expectedColumns =
        mapOf(
            "document" to
                "aggregate_id business_unit_id current_version direction doc_uuid document_date document_number " +
                "document_type_code entity_id entity_ref fiscal_year hold_reasons internal_reference " +
                "invoice_issued_or_received issuer_party_key partition_date posting_timestamp prescribed_period_id " +
                "prescribed_period_ref state tenant_id transaction_date",
            "document_version" to
                "canonical created_at created_by doc_uuid entity_id mapping_pack_version mde_pack_version " +
                "partition_date scope_pack_version scope_verdict specification_version tenant_id version",
            "document_line" to
                "description doc_uuid line_amount line_number quantity tax_amount tax_code tenant_id unit_price version",
            "document_subtotal" to "doc_uuid tax_amount tax_code taxable_amount tenant_id version",
            "document_party" to "doc_uuid party role tenant_id version",
        )

    @Test
    fun `INV-AC-19916 the canonical schema holds exactly the stored columns and no column for the sample values`() {
        expectedColumns.forEach { (table, expected) ->
            val columns =
                DocumentSql
                    .rows(
                        "select column_name from information_schema.columns where table_schema = 'canonical' " +
                            "and table_name = ? order by column_name",
                        table,
                    ).map { it.single() }
            columns shouldBe expected.split(" ")
        }
    }
}
