package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.events.AggregateReadyHandler
import com.dynokore.gst.canonical.application.aggregate.AggregateDocumentJson
import com.dynokore.gst.canonical.application.aggregate.AggregateEntityIds
import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.Classified
import com.dynokore.gst.canonical.application.aggregate.LineAnswer
import com.dynokore.gst.canonical.application.aggregate.LineClassifier
import com.dynokore.gst.canonical.application.aggregate.RecordAggregateDocument
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.Unavailable
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.networknt.schema.SpecificationVersion as SchemaDialect

/**
 * INV-AC-22852 through the real inbox consumer on the inbox, offset and dead-letter tables of schema `canonical`, with
 * the handler, the use case, the stores, the writer and the outbox publisher of the TPL-18 context over PostgreSQL
 * (P109). The rules and masterdata answers are fakes; the event-log read is a list in memory.
 */
class AggregateReadyConsumerTest {
    private val json = EnvelopeJson()
    private val mapper = JsonMapper.builder().build()

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private val rates = mapOf("S1" to ("SR" to "9.00"), "S2" to ("SR" to "9.00"), "Z1" to ("ZR" to "0.00"))
    private val identity = SupplierIdentity("0195:201912345A", "0195:201912345A", "201912345A")

    private inner class Boundary(
        val context: ApplicationContext,
    ) {
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()
        val tenant: UUID = UUID.randomUUID()
        val entityId: UUID = UUID.randomUUID()
        val consumerName = AggregateReadyConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val classified = mutableListOf<String>()
        var rulesRate: ((String) -> String?) = { code -> rates.getValue(code).second }
        var rulesUnavailable = false
        var supplier: SupplierAnswer = SupplierAnswer.Found(identity)
        var supplierCalls = 0

        private val classifier =
            LineClassifier { _, _, code, direction ->
                classified += "$code/$direction"
                if (rulesUnavailable) {
                    Unavailable
                } else {
                    Classified(
                        rates.getValue(code).first,
                        "IN_SCOPE",
                        mapOf("SCOPE" to "s1", "MAPPING" to "m1"),
                        rulesRate(code)?.let { BigDecimal(it) },
                    )
                }
            }
        private val supplierPort =
            AggregateSupplierIdentity { _, _, _ ->
                supplierCalls++
                supplier
            }
        private val handler =
            InboundTraceHandler(
                AggregateReadyHandler(
                    RecordAggregateDocument(
                        context.getBean("aggregateSourceEvents", RecordSourceEvent::class.java),
                        classifier,
                        context.getBean(AggregateEntityIds::class.java),
                        supplierPort,
                        context.getBean(AggregateDocumentJson::class.java),
                        context.getBean(CanonicalVersionWriter::class.java),
                    ),
                ),
                context.getBean(InboundEventTrace::class.java),
            )
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, AggregateReadyConsumerConfiguration.TOPIC, "canonical"),
                context.getBean(EventCatalogue::class.java),
                log,
                context.getBean(TenantScope::class.java),
                dsl,
                handler,
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )

        fun identityRow(ref: String = "ENT-1") =
            owner(
                "insert into canonical.entity_identity (tenant_id, entity_ref, entity_id, version) " +
                    "values ('$tenant', '$ref', '$entityId', 1)",
            )

        fun send(
            eventId: String,
            data: String,
        ) = log.add(envelope(tenant, eventId, data))

        fun documents() = owner("select count(*) from canonical.document where tenant_id = '$tenant'")

        fun versionRows() = count("select count(*) from canonical.document where tenant_id = '$tenant'")

        fun outboxRows() =
            count(
                "select count(*) from canonical.outbox where tenant_id = '$tenant' " +
                    "and type like '%CanonicalVersionCreated%'",
            )

        fun deadLetters() = count("select count(*) from canonical.event_dead_letter where consumer = '$consumerName'")

        fun canonical(): JsonNode =
            mapper.readTree(
                rows("select canonical::text from canonical.document_version where tenant_id = '$tenant'")
                    .single()
                    .single()!!,
            )

        fun inboxRows() = count("select count(*) from canonical.inbox where consumer = '$consumerName'")
    }

    private fun count(sql: String): Int = rows(sql).single().single()!!.toInt()

    private fun subtotalsOf(doc: JsonNode) =
        doc["taxSubtotals"]["value"].map {
            listOf(it["taxCode"].asString(), it["taxableAmount"].asString(), it["taxAmount"].asString())
        }

    private fun owner(sql: String) {
        CanonicalDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }
    }

    private fun rows(sql: String): List<List<String?>> =
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    val width = r.metaData.columnCount
                    buildList { while (r.next()) add((1..width).map { c -> r.getString(c) }) }
                }
            }
        }

    private fun run(
        dir: Path,
        block: Boundary.() -> Unit,
    ) {
        CanonicalContext.online(dir).run { context -> Boundary(context).block() }
    }

    private fun envelope(
        tenant: UUID,
        eventId: String,
        data: String,
    ) = json.write(
        Envelope(
            id = EventId(eventId),
            source = "/gst/aggregation-svc",
            type = "com.dynokore.gst.aggregation.AggregateReady.v1",
            subject = "AGG-1",
            time = Instant.parse("2026-11-12T10:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("ENT-1"),
            docUuid = DocUuid(UUID.randomUUID()),
            transactionDate = LocalDate.of(2026, 1, 31),
            docSequence = eventId.removePrefix("ev-").toLong(),
            instanceId = "aggregation-svc-0",
            correlation = CorrelationId("corr-$eventId"),
            causation = CausationId("cause-$eventId"),
            dataJson = data,
        ),
    )

    private fun line(
        code: String,
        amount: String,
        taxable: String? = null,
    ) = """{"internalTaxCode":"$code","amount":$amount${taxable?.let { ""","taxableAmount":$it""" }.orEmpty()}}"""

    private val salesLines = listOf(line("S1", "\"100.00\""), line("S2", "\"50.25\""), line("Z1", "\"10.00\""))
    private val purchaseLines =
        listOf(
            line("S1", "\"9.00\"", "\"100.00\""),
            line("S2", "\"4.00\"", "\"50.00\""),
            line("Z1", "\"0.00\"", "\"20.00\""),
        )

    private fun data(
        lines: List<String> = salesLines,
        id: String? = "\"AGG-1\"",
        to: String? = "\"2026-01-31\"",
        type: String = "POS",
    ) = buildString {
        append("{")
        id?.let { append("\"aggregateId\":$it,") }
        append("\"entityRef\":\"ENT-1\",\"aggregateType\":\"$type\",")
        append("\"window\":{\"from\":\"2026-01-01\",${to?.let { "\"to\":$it," }.orEmpty()}\"periodRef\":\"2026-01\"},")
        append("\"linesByInternalTaxCode\":[${lines.joinToString(",")}]}")
    }

    @Test
    fun `INV-AC-22852 consumer subscription equals canonical-svc, the topic and schema canonical`(
        @TempDir dir: Path,
    ) = run(dir) {
        val bean = context.getBean("aggregateReadyConsumer", JdbcInboxConsumer::class.java)
        val field = JdbcInboxConsumer::class.java.getDeclaredField("subscription")
        field.isAccessible = true
        field.get(bean) shouldBe
            ConsumerSubscription("canonical-svc", "gst.aggregation.aggregate-ready.v1", "canonical")
    }

    @Test
    fun `INV-AC-22852 valid event writes one document and one outbox row`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        versionRows() shouldBe 1
        outboxRows() shouldBe 1
        classified shouldBe listOf("S1/SALE", "S2/SALE", "Z1/SALE")
    }

    @Test
    fun `INV-AC-22852 redelivery writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        send("ev-2", data()) // the same aggregate under a new event id
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        versionRows() shouldBe 1
        outboxRows() shouldBe 1
    }

    @Test
    fun `INV-AC-22852 event without window end is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data(to = null))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 event without aggregate id is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data(id = null))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 numeric amount is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data(lines = listOf(line("S1", "12"))))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 taxable amount that is a number is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data(lines = listOf(line("S1", "\"9.00\"", "100")), type = "PCP"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versionRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 entity without identity row is retried`(
        @TempDir dir: Path,
    ) = run(dir) {
        send("ev-1", data())
        runCatching { consumer.pollOnce() }.isFailure shouldBe true
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
        inboxRows() shouldBe 0
        deadLetters() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 unavailable rules is retried`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        rulesUnavailable = true
        send("ev-1", data())
        runCatching { consumer.pollOnce() }.isFailure shouldBe true
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
        deadLetters() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 unavailable masterdata resolution is retried`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        supplier = SupplierAnswer.Retry("masterdata down")
        send("ev-1", data())
        runCatching { consumer.pollOnce() }.isFailure shouldBe true
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
        deadLetters() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 entity without a known constitution is retried`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        supplier = SupplierAnswer.Retry("constitution unknown")
        send("ev-1", data())
        val failure = runCatching { consumer.pollOnce() }.exceptionOrNull()
        (failure?.message ?: "").contains("constitution unknown") shouldBe true
        versionRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 masterdata refusal MDM-005 is retried`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        supplier = SupplierAnswer.Retry("masterdata refused: status 422 code MDM-005")
        send("ev-1", data())
        val failure = runCatching { consumer.pollOnce() }.exceptionOrNull()
        (failure?.message ?: "").contains("MDM-005") shouldBe true
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
        deadLetters() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 event without a rate in the rules answer is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        rulesRate = { null }
        send("ev-1", data())
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 aggregate document has SGD currency, no exchange rate and an empty buyer`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        consumer.pollOnce()
        val doc = canonical()
        doc["currency"]["value"].asString() shouldBe "SGD"
        doc["taxCurrencyCode"]["value"].asString() shouldBe "SGD"
        doc.has("exchangeRate") shouldBe false
        doc["parties"]["value"]["buyer"].toString() shouldBe "{}"
        doc["parties"]["classification"].asString() shouldBe "IDENTIFYING"
    }

    @Test
    fun `INV-AC-22852 aggregate document carries the aggregate type and the identity triple`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        consumer.pollOnce()
        val doc = canonical()
        doc["aggregateType"]["value"].asString() shouldBe "POS"
        doc["aggregateType"]["classification"].asString() shouldBe "NONE"
        val triple = doc["identityTriple"]
        triple["classification"].asString() shouldBe "IDENTIFYING"
        triple["value"]["senderId"].asString() shouldBe identity.senderId
        triple["value"]["supplierId"].asString() shouldBe identity.supplierId
        triple["value"]["supplierGstn"].asString() shouldBe identity.supplierGstn
        supplierCalls shouldBe 1
    }

    @Test
    fun `INV-AC-22852 supplier is the object with gstn only`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        consumer.pollOnce()
        val supplierNode = canonical()["parties"]["value"]["supplier"]
        supplierNode.propertyNames().toList() shouldBe listOf("gstn")
        supplierNode["gstn"].asString() shouldBe identity.supplierGstn
    }

    @Test
    fun `INV-AC-22852 aggregate document states tax subtotals and totals from the amounts`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        consumer.pollOnce()
        val doc = canonical()
        doc["taxSubtotals"]["classification"].asString() shouldBe "NONE"
        subtotalsOf(doc) shouldBe listOf(listOf("SR", "150.25", "13.52"), listOf("ZR", "10.00", "0.00"))
        doc["totals"]["classification"].asString() shouldBe "NONE"
        val totals = doc["totals"]["value"]
        listOf("totalExcludingTax", "totalTax", "totalIncludingTax").map { totals[it].asString() } shouldBe
            listOf("160.25", "13.52", "173.77")
    }

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `INV-AC-22852 PCP event with taxable amounts writes one PURCHASE document with tax subtotals and totals from the amounts`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        rulesRate = { null }
        send("ev-1", data(lines = purchaseLines, type = "PCP"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        classified shouldBe listOf("S1/PURCHASE", "S2/PURCHASE", "Z1/PURCHASE")
        rows("select direction from canonical.document where tenant_id = '$tenant'")
            .single()
            .single() shouldBe "PURCHASE"
        val doc = canonical()
        doc["aggregateType"]["value"].asString() shouldBe "PCP"
        subtotalsOf(doc) shouldBe
            listOf(listOf("SR", "150.00", "13.00"), listOf("ZR", "20.00", "0.00"))
        val totals = doc["totals"]["value"]
        listOf("totalExcludingTax", "totalTax", "totalIncludingTax").map { totals[it].asString() } shouldBe
            listOf("170.00", "13.00", "183.00")
    }

    @Test
    fun `INV-AC-22852 PCP event with a line without taxable amount is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data(lines = listOf(purchaseLines[0], line("S2", "\"4.00\"")), type = "PCP"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versionRows() shouldBe 0
        outboxRows() shouldBe 0
    }

    @Test
    fun `INV-AC-22852 built canonical document validates against the pinned schema`(
        @TempDir dir: Path,
    ) = run(dir) {
        identityRow()
        send("ev-1", data())
        consumer.pollOnce()
        schemaErrors(canonical().toString()).shouldBeEmpty()
        // the schema is strict: a document without its currency fails
        val without = canonical().let { (it as ObjectNode).also { n -> n.remove("currency") } }
        (schemaErrors(without.toString()).isNotEmpty()) shouldBe true
    }

    /** The `canonicalDocument` definition of the pinned CanonicalVersionCreated schema. */
    private fun schemaErrors(document: String): List<String> {
        val dir = File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })
        val full = mapper.readTree(File(dir, "schemas/canonical-version-created-payload.schema.json").readText())
        val definition = full["properties"]["canonicalDocument"]
        val registry =
            SchemaRegistry.withDefaultDialect(SchemaDialect.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry.getSchema(definition.toString()).validate(mapper.readTree(document)).map { it.toString() }
    }
}
