package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.application.sample.SampleDocumentFunctions
import com.dynokore.gst.canonical.application.sample.SamplePeriodCalendar
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.networknt.schema.SpecificationVersion as SchemaDialect

/** The fixtures and the plumbing the two exchange test classes share (P109: the real context, real handler bean). */
internal object SampleExchangeKit {
    const val SCOPE_PACK = "gst-scope-it"
    const val REQUEST_TYPE = "com.dynokore.gst.platform.PackDryRunSampleRequested.v1"
    const val RECORDED_TYPE = "com.dynokore.gst.platform.PackDryRunSampleRecorded.v1"
    val mapper: JsonMapper = JsonMapper.builder().build()
    private val envelopes = EnvelopeJson()

    fun schemaFile(): File {
        val dir = File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })
        return File(dir.parentFile, "service-runtime/schemas/pack-dry-run-sample-recorded-payload.schema.json")
    }

    val maxItems: Int by lazy {
        mapper
            .readTree(schemaFile().readText())
            .path("properties")
            .path("documents")
            .path("maxItems")
            .asInt()
    }

    private val registry =
        SchemaRegistry.withDefaultDialect(SchemaDialect.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    fun schemaErrors(payload: JsonNode): List<String> =
        registry.getSchema(schemaFile().readText()).validate(payload).map { it.toString() }

    fun context(dir: Path) =
        CanonicalContext.online(dir).withPropertyValues("gst.sample.recorded-schema-file=${schemaFile().path}")

    fun owner(sql: String) {
        CanonicalDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }
    }

    fun rows(sql: String): List<List<String?>> =
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    val width = r.metaData.columnCount
                    buildList { while (r.next()) add((1..width).map { c -> r.getString(c) }) }
                }
            }
        }

    /** Both scope-pack rows of the replica, once for the whole database: 380 and 381 have a function. */
    fun seedScopePack() {
        owner(
            "insert into canonical.scope_pack_version values ('$SCOPE_PACK', '1.0.0', now()) on conflict do nothing; " +
                "insert into canonical.scope_pack_function values ('$SCOPE_PACK', '1.0.0', 380, 'BILL_FOR_PAYMENT') " +
                "on conflict do nothing; " +
                "insert into canonical.scope_pack_function values ('$SCOPE_PACK', '1.0.0', 381, 'ADJUSTMENT') " +
                "on conflict do nothing; " +
                "insert into canonical.scope_pack_activation values ('$SCOPE_PACK', '1.0.0', '2026-01-01', now()) " +
                "on conflict do nothing",
        )
    }

    class Log : EventLogReader {
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

    class Sub(
        val code: String,
        val taxable: String,
        val tax: String,
    )

    fun canonical(
        subtotals: List<Sub>,
        preceding: String? = null,
        supplyType: String? = null,
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
        val before =
            preceding?.let { """"precedingDocumentDate":{"value":"$it","classification":"NONE"},""" }.orEmpty() +
                supplyType?.let { """"supplyType":{"value":"$it","classification":"NONE"},""" }.orEmpty()
        return """{"currency":{"value":"SGD","classification":"NONE"},$before""" +
            """"totals":{"value":{"totalExcludingTax":"999.00"},"classification":"NONE"},""" +
            """"parties":{"value":{"supplier":{},"buyer":{}},"classification":"content"},""" +
            """"lines":{"value":[$lines],"classification":"content"},""" +
            """"taxSubtotals":{"value":[$subs],"classification":"content"}}"""
    }

    /** One seeded document and the item the sample must hold for it. */
    @Suppress("LongParameterList") // a fixture row: one argument per member of the stored version
    class Seeded(
        val docUuid: UUID,
        val on: LocalDate,
        val code: Int,
        val taxable: String,
        val tax: String,
        val preceding: String?,
        val supplyType: String?,
    ) {
        fun expected(entityRef: String): Map<String, Any> =
            buildMap {
                put("entityRef", entityRef)
                put("transactionDate", on.toString())
                put("internalTaxCode", "SR")
                put("documentFunction", if (code == 381) "ADJUSTMENT" else "BILL_FOR_PAYMENT")
                preceding?.let { put("precedingDocumentDate", it) }
                put("taxableValue", taxable)
                put("gstValue", tax)
                put("periodRef", "2026-03")
                put("docUuid", docUuid.toString())
                supplyType?.let { put("supplyType", it) }
            }
    }

    class Harness(
        val context: ApplicationContext,
        val actor: String = SignedBindings.ACTOR,
    ) {
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val runner = context.getBean(TransactionRunner::class.java)
        val log = Log()
        val consumerName = SampleConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val handler: EventHandler = context.getBean("sampleRequestHandler", EventHandler::class.java)
        val tenant: UUID = UUID.randomUUID()
        val entityId: UUID = UUID.randomUUID()
        private var events = 0

        // a document id is unique in the database: a positive high half keeps Java's and PostgreSQL's order equal
        private val docHigh =
            java.util.concurrent.ThreadLocalRandom
                .current()
                .nextLong() and Long.MAX_VALUE

        private var offset = java.time.Duration.ZERO

        /** The consumer's clock: a failed handler is retried after a backoff, which the test steps over. */
        private val clock =
            object : Clock() {
                override fun getZone() = Clock.systemUTC().zone

                override fun withZone(zone: java.time.ZoneId) = this

                override fun instant(): Instant = Instant.now().plus(offset)
            }

        fun skipBackoff() {
            offset = offset.plus(java.time.Duration.ofHours(1))
        }

        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, SampleConfiguration.TOPIC, "canonical"),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                handler,
                RebuildRequiredSink { },
                clock,
            )

        /** Entity identity and period spans of [ref] for [owner]; the scope pack is seeded once. */
        fun seedEntity(
            ref: String = "ENT-A",
            owner: UUID = tenant,
            id: UUID = entityId,
        ) {
            SampleExchangeKit.seedScopePack()
            SampleExchangeKit.owner(
                "insert into canonical.entity_identity (tenant_id, entity_ref, entity_id, version) " +
                    "values ('$owner', '$ref', '$id', 1); " +
                    "insert into canonical.period_span_version (tenant_id, entity_ref, version) " +
                    "values ('$owner', '$ref', 1); " +
                    "insert into canonical.period_span (tenant_id, entity_ref, period_ref, period_start, period_end) " +
                    "values ('$owner', '$ref', '2026-03', '2026-03-01', '2026-03-31'), " +
                    "('$owner', '$ref', '2026-04', '2026-04-01', '2026-04-30')",
            )
        }

        fun seedDocuments(
            documents: List<Seeded>,
            owner: UUID = tenant,
            entity: UUID = entityId,
        ) {
            val store = JooqDocumentStore(dsl)
            scope.bind(TenantId(owner), actor) {
                runner.inTransaction {
                    documents.forEach { store.insert(newDocument(owner, entity, it)) }
                }
            }
        }

        private fun newDocument(
            owner: UUID,
            entity: UUID,
            d: Seeded,
        ) = NewDocument(
            d.docUuid,
            owner,
            entity,
            null,
            Direction.SALE,
            d.code.toShort(),
            "N-" + d.docUuid,
            "issuer",
            2026,
            null,
            DocumentState.READY,
            null,
            NewVersion(
                canonical(listOf(Sub("SR", d.taxable, d.tax)), d.preceding, d.supplyType),
                ScopeVerdict.IN_SCOPE,
                "scope-1",
                "map-1",
                null,
                SpecificationVersion.PINT_SG,
                d.on,
                "svc",
            ),
        )

        fun send(
            dryRunId: String,
            entityRef: String = "ENT-A",
            owner: UUID = tenant,
            first: String = "2026-03",
            last: String = "2026-04",
        ) {
            events++
            val id = "ev-$events"
            val data =
                """{"dryRunId":"$dryRunId","entityRef":"$entityRef","firstPeriodRef":"$first",""" +
                    """"lastPeriodRef":"$last",""" +
                    """"periodCount":2,"currentPackVersion":"1.0.0","candidatePackVersion":"1.1.0"}"""
            log.add(
                envelopes.write(
                    Envelope(
                        id = EventId(id),
                        source = "/gst/rules-svc",
                        type = REQUEST_TYPE,
                        subject = dryRunId,
                        time = Instant.parse("2026-11-12T10:00:00Z"),
                        tenant = TenantId(owner),
                        entity = EntityRef(entityRef),
                        docUuid = null,
                        transactionDate = null,
                        docSequence = null,
                        instanceId = "rules-svc-0",
                        correlation = CorrelationId("corr-$id"),
                        causation = CausationId("cause-$id"),
                        dataJson = data,
                    ),
                ),
            )
        }

        /** The outbox rows of the recorded type of [owner] for [dryRunId]: (entity_ref, data), in write order. */
        fun pages(
            dryRunId: String,
            owner: UUID = tenant,
        ): List<Pair<String, JsonNode>> =
            rows(
                "select entity_ref, data::text from canonical.outbox where tenant_id = '$owner' " +
                    "and type = '$RECORDED_TYPE' and topic = 'gst.platform.pack-dry-run-sample-recorded.v1' " +
                    "and data->>'dryRunId' = '$dryRunId' order by seq",
            ).map { it[0]!! to mapper.readTree(it[1]!!) }

        fun markers(
            dryRunId: String,
            owner: UUID = tenant,
        ): Int =
            rows(
                "select count(*) from canonical.sample_request_marker where tenant_id = '$owner' " +
                    "and dry_run_id = '$dryRunId'",
            ).single().single()!!.toInt()

        fun poll(): PollResult = consumer.pollOnce()

        fun entry(
            n: Int,
            code: Int = 380,
        ) = Seeded(
            UUID(docHigh, n.toLong() + 1),
            LocalDate.of(2026, 3, 1 + n % 28),
            code,
            "${100 + n}.00",
            "9.00",
            null,
            null,
        )
    }
}

/** INV-REQ-772 b1, INV-REQ-1081: the sample request consumed from the inbox and answered through the outbox. */
class SampleExchangeIT {
    private fun run(
        dir: Path,
        block: SampleExchangeKit.Harness.() -> Unit,
    ) {
        SampleExchangeKit.context(dir).run { SampleExchangeKit.Harness(it).block() }
    }

    private val three = { h: SampleExchangeKit.Harness -> (1..3).map { h.entry(it) } }

    @Test
    fun `INV-AC-19914 the ports are the Jooq adapters, compared by fully qualified class name`(
        @TempDir dir: Path,
    ) = run(dir) {
        val functions = context.getBeansOfType(SampleDocumentFunctions::class.java).values.toList()
        val calendars = context.getBeansOfType(SamplePeriodCalendar::class.java).values.toList()
        functions.map { it.javaClass.name } shouldBe
            listOf("com.dynokore.gst.canonical.adapters.persistence.JooqScopePackReplica")
        calendars.map { it.javaClass.name } shouldBe
            listOf("com.dynokore.gst.canonical.adapters.persistence.JooqSamplePeriodCalendar")
    }

    @Test
    fun `INV-AC-19914 a request of tenant A is answered with pages of tenant A only`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        val mine = three(this)
        seedDocuments(mine)
        val otherTenant = UUID.randomUUID()
        val otherEntity = UUID.randomUUID()
        seedEntity("ENT-A", otherTenant, otherEntity)
        seedDocuments(listOf(entry(40)), otherTenant, otherEntity)
        send("dry-a")
        poll() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        val pages = pages("dry-a")
        pages.size shouldBe 1
        pages.single().first shouldBe "ENT-A"
        val ids =
            pages
                .single()
                .second
                .path("documents")
                .toList()
                .map { it.path("docUuid").asString() }
        ids.toSet() shouldBe mine.map { it.docUuid.toString() }.toSet()
        pages("dry-a", otherTenant).size shouldBe 0
    }

    @Test
    fun `INV-AC-19914 a request whose envelope names tenant B never reads the documents of tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seedDocuments(three(this))
        val tenantB = UUID.randomUUID()
        // tenant B has its own entity of the same reference, with no document
        seedEntity("ENT-A", tenantB, UUID.randomUUID())
        send("dry-b", owner = tenantB)
        poll().applied shouldBe 1
        val pageB = pages("dry-b", tenantB).single().second
        pageB.path("documents").size() shouldBe 0
        pageB.path("lastPage").asBoolean() shouldBe true
        pages("dry-b", tenant).size shouldBe 0
    }

    @Test
    fun `INV-AC-19914 a duplicate request with the same dry-run id leaves the pages of the first`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seedDocuments(three(this))
        send("dry-dup")
        poll().applied shouldBe 1
        val first = pages("dry-dup")
        send("dry-dup")
        poll() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        pages("dry-dup").map { it.second } shouldBe first.map { it.second }
        markers("dry-dup") shouldBe 1
    }

    @Test
    fun `INV-AC-19914 the same dry-run id for tenant B is answered`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seedDocuments(three(this))
        val tenantB = UUID.randomUUID()
        val entityB = UUID.randomUUID()
        seedEntity("ENT-A", tenantB, entityB)
        seedDocuments(listOf(entry(50)), tenantB, entityB)
        send("dry-same")
        send("dry-same", owner = tenantB)
        poll().applied shouldBe 2
        pages("dry-same").size shouldBe 1
        pages("dry-same", tenantB).size shouldBe 1
        pages("dry-same", tenantB)
            .single()
            .second
            .path("documents")
            .size() shouldBe 1
    }

    @Test
    fun `INV-AC-19914 a request the use case refuses leaves no marker and a later retry is answered`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seedDocuments(listOf(entry(1, code = 751)))
        send("dry-refused")
        runCatching { poll() }.isFailure shouldBe true
        markers("dry-refused") shouldBe 0
        pages("dry-refused").size shouldBe 0
        // the scope pack now classifies the code: the same request, retried from the same offset, is answered
        SampleExchangeKit.owner(
            "update canonical.document set document_type_code = 380 where tenant_id = '$tenant'",
        )
        skipBackoff()
        poll().applied shouldBe 1
        markers("dry-refused") shouldBe 1
        pages("dry-refused").size shouldBe 1
    }

    @Test
    fun `INV-AC-19914 a dry-run id of 40 characters is answered`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seedDocuments(three(this))
        val id = "d".repeat(40)
        send(id)
        poll().applied shouldBe 1
        pages(id).size shouldBe 1
        markers(id) shouldBe 1
    }

    @Test
    fun `INV-AC-19914 an unknown entityRef is refused with no page and no marker`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        send("dry-unknown", entityRef = "ENT-NONE")
        runCatching { poll() }.isFailure shouldBe true
        pages("dry-unknown").size shouldBe 0
        markers("dry-unknown") shouldBe 0
    }

    @Test
    fun `INV-AC-19914 an entityRef that exists only for another tenant is refused`(
        @TempDir dir: Path,
    ) = run(dir) {
        val other = UUID.randomUUID()
        seedEntity("ENT-ONLY-B", other, UUID.randomUUID())
        send("dry-foreign", entityRef = "ENT-ONLY-B")
        runCatching { poll() }.isFailure shouldBe true
        pages("dry-foreign").size shouldBe 0
        markers("dry-foreign") shouldBe 0
    }

    @Test
    fun `INV-AC-19914 for a window without a document the event row holds the entityRef of the request`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity("ENT-Z")
        send("dry-empty", entityRef = "ENT-Z")
        poll().applied shouldBe 1
        val row = pages("dry-empty").single()
        row.first shouldBe "ENT-Z"
        (row.first == "ENT-A") shouldBe false
        row.second.path("documents").size() shouldBe 0
    }

    private fun SampleExchangeKit.Harness.fullWindow(max: Int): List<SampleExchangeKit.Seeded> =
        (1..max).map {
            val e = entry(it)
            when (it) {
                1 -> SampleExchangeKit.Seeded(e.docUuid, e.on, 381, "-5.00", "-0.45", "2026-03-02", null)
                2 -> SampleExchangeKit.Seeded(e.docUuid, e.on, 380, e.taxable, e.tax, null, "STANDARD_RATED")
                else -> e
            }
        }

    private fun assertSameMembers(
        items: List<JsonNode>,
        expected: List<JsonNode>,
    ) {
        items.indices.forEach { i ->
            val got = items[i]
            val want = expected[i]
            want.propertyNames().forEach { member -> got.path(member) shouldBe want.path(member) }
            got.propertyNames().asSequence().toSet() shouldBe want.propertyNames().asSequence().toSet()
        }
    }

    @Test
    fun `INV-AC-19914 a window of exactly maxItems documents is one last page that round trips unchanged`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        val max = SampleExchangeKit.maxItems
        val seeded = fullWindow(max)
        seedDocuments(seeded)
        send("dry-full")
        poll().applied shouldBe 1
        val pages = pages("dry-full")
        pages.size shouldBe 1
        val payload = pages.single().second
        payload.path("lastPage").asBoolean() shouldBe true
        payload.path("pageNumber").asInt() shouldBe 1
        payload.path("dryRunId").asString() shouldBe "dry-full"
        val items = payload.path("documents").toList()
        items.size shouldBe max
        val expected =
            seeded.sortedBy { it.docUuid }.map {
                SampleExchangeKit.mapper.valueToTree<JsonNode>(it.expected("ENT-A"))
            }
        assertSameMembers(items, expected)
        SampleExchangeKit.schemaErrors(payload) shouldBe emptyList()
        val bytes =
            SampleExchangeKit
                .rows(
                    "select octet_length(data::text) from canonical.outbox where tenant_id = '$tenant' " +
                        "and type = '${SampleExchangeKit.RECORDED_TYPE}' and data->>'dryRunId' = 'dry-full'",
                ).single()
                .single()!!
                .toInt()
        (bytes < 1_000_000) shouldBe true
    }

    @Test
    fun `INV-AC-19914 every page read back validates against the schema and a second page holds the rest`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        val max = SampleExchangeKit.maxItems
        seedDocuments((1..max + 1).map { entry(it) })
        send("dry-two")
        poll().applied shouldBe 1
        val pages = pages("dry-two").map { it.second }
        pages.map { it.path("pageNumber").asInt() } shouldBe listOf(1, 2)
        pages.map { it.path("lastPage").asBoolean() } shouldBe listOf(false, true)
        pages.map { it.path("documents").size() } shouldBe listOf(max, 1)
        pages.forEach { SampleExchangeKit.schemaErrors(it) shouldBe emptyList() }
    }

    @Test
    fun `INV-AC-19914 the page limit is the maxItems of the schema file`() {
        SampleConfiguration.maxItems(SampleExchangeKit.schemaFile().path) shouldBe SampleExchangeKit.maxItems
        runCatching { SampleConfiguration.maxItems("") }.isFailure shouldBe true
        runCatching { SampleConfiguration.maxItems("/nonexistent/schema.json") }.isFailure shouldBe true
    }
}
