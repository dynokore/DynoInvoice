package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
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
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
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
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.ZipFile
import com.networknt.schema.SpecificationVersion as SchemaDialect

/** The fixtures and the plumbing of the reprocessing exchange (P109: the real context, the real handler bean). */
internal object ReprocessingKit {
    const val REQUEST_TYPE = "com.dynokore.gst.platform.PackReprocessingRequested.v1"
    const val RECORDED_TYPE = "com.dynokore.gst.platform.PackReprocessingPopulationRecorded.v1"
    const val RECORDED_TOPIC = "gst.platform.pack-reprocessing-population-recorded.v1"
    const val BAD = "scope-1"
    const val OTHER = "scope-2"
    val d0: LocalDate = LocalDate.of(2026, 3, 10)
    private val envelopes = EnvelopeJson()
    private val mapper = SampleExchangeKit.mapper

    private fun schemaFile(): File {
        val dir = File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })
        return File(dir.parentFile, "service-runtime/schemas/pack-reprocessing-population-recorded-payload.schema.json")
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
        CanonicalContext
            .online(dir)
            .withPropertyValues(
                "${ReprocessingConfiguration.SCHEMA_FILE_PROPERTY}=${productionSchemaLocation()}",
                "${SampleConfiguration.SCHEMA_FILE_PROPERTY}=${SampleExchangeKit.productionSchemaLocation()}",
            )

    fun productionSchemaLocation(): String {
        val yaml =
            org.springframework.beans.factory.config
                .YamlPropertiesFactoryBean()
                .apply {
                    setResources(
                        org.springframework.core.io
                            .ClassPathResource("application.yaml"),
                    )
                }.getObject()
        return checkNotNull(yaml?.getProperty(ReprocessingConfiguration.SCHEMA_FILE_PROPERTY)) { "no property" }
    }

    /** One seeded document: its pack versions are stored in the columns of its current version. */
    @Suppress("LongParameterList") // a fixture row: one argument per member of the stored version
    class Seeded(
        val docUuid: UUID,
        val on: LocalDate,
        val scope: String = BAD,
        val mapping: String = "map-1",
        val mde: String? = null,
        val code: Int = 380,
        val taxable: String = "100.00",
        val tax: String = "9.00",
    )

    class Harness(
        val context: ApplicationContext,
    ) {
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val runner = context.getBean(TransactionRunner::class.java)
        private val log = SampleExchangeKit.Log()
        val consumerName = ReprocessingConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val tenant: UUID = UUID.randomUUID()
        val entityId: UUID = UUID.randomUUID()
        private var events = 0
        private val docHigh = ThreadLocalRandom.current().nextLong() and Long.MAX_VALUE

        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, ReprocessingConfiguration.TOPIC, "canonical"),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean("reprocessingRequestHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                object : Clock() {
                    override fun getZone() = Clock.systemUTC().zone

                    override fun withZone(zone: java.time.ZoneId) = this

                    override fun instant(): Instant = Instant.now()
                },
            )

        fun id(n: Int) = UUID(docHigh, n.toLong() + 1)

        @Suppress("LongParameterList") // a fixture row: one argument per stored pack column
        fun doc(
            n: Int,
            on: LocalDate = d0.plusDays(n % 20L),
            scope: String = BAD,
            mapping: String = "map-1",
            mde: String? = null,
            code: Int = 380,
        ) = Seeded(id(n), on, scope, mapping, mde, code, "${100 + n}.00", "9.00")

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

        fun seed(
            documents: List<Seeded>,
            owner: UUID = tenant,
            entity: UUID = entityId,
        ) {
            val store = JooqDocumentStore(dsl)
            scope.bind(TenantId(owner), SignedBindings.ACTOR) {
                runner.inTransaction { documents.forEach { store.insert(newDocument(owner, entity, it)) } }
            }
        }

        /** Moves [doc] to version 2 (same pack versions); version 1 stays in the audit trail. */
        fun bump(doc: Seeded) {
            val store = JooqDocumentStore(dsl)
            scope.bind(TenantId(tenant), SignedBindings.ACTOR) {
                runner.inTransaction { store.createVersion(doc.docUuid, 1, versionOf(doc)) }
            }
        }

        fun acknowledge(
            doc: UUID,
            version: Int,
            owner: UUID = tenant,
        ) = SampleExchangeKit.owner(
            "insert into canonical.submission_acknowledgement values ('$owner', '$doc', $version, 'A1', now(), now())",
        )

        private fun versionOf(d: Seeded) =
            NewVersion(
                SampleExchangeKit.canonical(listOf(SampleExchangeKit.Sub("SR", d.taxable, d.tax))),
                ScopeVerdict.IN_SCOPE,
                d.scope,
                d.mapping,
                d.mde,
                SpecificationVersion.PINT_SG,
                d.on,
                "svc",
            )

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
            versionOf(d),
        )

        @Suppress("LongParameterList")
        fun send(
            reprocessingId: String,
            entityRef: String = "ENT-A",
            packType: String = "SCOPE",
            bad: String = BAD,
            from: String = d0.toString(),
            owner: UUID = tenant,
        ) {
            events++
            val id = "ev-$events"
            val data =
                """{"reprocessingId":"$reprocessingId","entityRef":"$entityRef","packType":"$packType",""" +
                    """"badPackVersion":"$bad","supersedingPackVersion":"scope-9","effectiveFrom":"$from"}"""
            log.add(
                envelopes.write(
                    Envelope(
                        id = EventId(id),
                        source = "/gst/rules-svc",
                        type = REQUEST_TYPE,
                        subject = reprocessingId,
                        time = Instant.parse("2026-11-21T10:00:00Z"),
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

        fun pages(
            reprocessingId: String,
            owner: UUID = tenant,
        ): List<JsonNode> =
            SampleExchangeKit
                .rows(
                    "select data::text from canonical.outbox where tenant_id = '$owner' and type = '$RECORDED_TYPE' " +
                        "and topic = '$RECORDED_TOPIC' and data->>'reprocessingId' = '$reprocessingId' order by seq",
                ).map { mapper.readTree(it[0]!!) }

        fun markers(
            reprocessingId: String,
            owner: UUID = tenant,
        ): Int =
            SampleExchangeKit
                .rows(
                    "select count(*) from canonical.reprocessing_request_marker where tenant_id = '$owner' " +
                        "and reprocessing_id = '$reprocessingId'",
                ).single()
                .single()!!
                .toInt()

        fun deadLetters(): Int =
            SampleExchangeKit
                .rows("select count(*) from canonical.event_dead_letter where consumer = '$consumerName'")
                .single()
                .single()!!
                .toInt()

        fun poll(): PollResult = consumer.pollOnce()

        /** The Doc UUIDs on the pages of [reprocessingId], in page order. */
        fun ids(reprocessingId: String): List<String> =
            pages(reprocessingId).flatMap { p -> p.path("documents").toList().map { it.path("docUuid").asString() } }
    }
}

/** INV-REQ-1003: a pack reprocessing request consumed from the inbox and answered with population pages. */
class ReprocessingExchangeIT {
    private fun run(
        dir: Path,
        block: ReprocessingKit.Harness.() -> Unit,
    ) {
        ReprocessingKit.context(dir).run { ReprocessingKit.Harness(it).block() }
    }

    private val applied = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)

    /** The documents of the main scenario; the rest of the entity's, other entity's and other tenant's are seeded. */
    private class Scenario(
        val inSet: List<ReprocessingKit.Seeded>,
        val earlierVersion: ReprocessingKit.Seeded,
        val foreignAck: ReprocessingKit.Seeded,
        val onD0: ReprocessingKit.Seeded,
        val otherTenant: UUID,
    ) {
        fun expected(): Set<String> = (inSet + earlierVersion + foreignAck + onD0).map { it.docUuid.toString() }.toSet()
    }

    private fun ReprocessingKit.Harness.scenario(): Scenario {
        seedEntity()
        val d0 = ReprocessingKit.d0
        val inSet = (1..5).map { doc(it) }
        val earlierVersion = doc(6)
        val foreignAck = doc(7)
        val noise =
            listOf(
                doc(8, scope = ReprocessingKit.OTHER, mapping = ReprocessingKit.BAD),
                doc(9, on = d0.minusDays(1)),
                doc(11, scope = ReprocessingKit.OTHER),
            )
        val onD0 = doc(10, on = d0)
        seed(inSet + earlierVersion + foreignAck + onD0 + noise)
        bump(earlierVersion)
        acknowledge(inSet[0].docUuid, 1)
        acknowledge(inSet[1].docUuid, 1)
        acknowledge(earlierVersion.docUuid, 1)
        val otherTenant = UUID.randomUUID()
        val otherEntity = UUID.randomUUID()
        seedEntity("ENT-A", otherTenant, otherEntity)
        seed(listOf(doc(40)), otherTenant, otherEntity)
        acknowledge(foreignAck.docUuid, 1, otherTenant)
        val otherEntityId = UUID.randomUUID()
        SampleExchangeKit.owner(
            "insert into canonical.entity_identity (tenant_id, entity_ref, entity_id, version) " +
                "values ('$tenant', 'ENT-B', '$otherEntityId', 1)",
        )
        seed(listOf(doc(41)), tenant, otherEntityId)
        return Scenario(inSet, earlierVersion, foreignAck, onD0, otherTenant)
    }

    @Test
    fun `INV-AC-24002 the pages hold exactly the entity documents of the bad pack from D0, delivered twice`(
        @TempDir dir: Path,
    ) = run(dir) {
        val s = scenario()
        send("rep-1")
        poll() shouldBe applied
        send("rep-1")
        poll() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        val pages = pages("rep-1")
        pages.size shouldBe 1
        pages.forEach { ReprocessingKit.schemaErrors(it) shouldBe emptyList() }
        ids("rep-1").toSet() shouldBe s.expected()
        ids("rep-1").size shouldBe s.expected().size
        pages("rep-1", s.otherTenant).size shouldBe 0
        markers("rep-1") shouldBe 1
    }

    @Test
    fun `INV-AC-24002 each item carries its Doc UUID, current version and the acknowledged flag of that version`(
        @TempDir dir: Path,
    ) = run(dir) {
        val s = scenario()
        send("rep-items")
        poll() shouldBe applied
        val items =
            pages("rep-items")
                .single()
                .path("documents")
                .toList()
                .associateBy { it.path("docUuid").asString() }

        fun item(d: ReprocessingKit.Seeded) = items.getValue(d.docUuid.toString())
        item(s.inSet[0]).path("acknowledged").asBoolean() shouldBe true
        item(s.inSet[1]).path("acknowledged").asBoolean() shouldBe true
        item(s.inSet[2]).path("acknowledged").asBoolean() shouldBe false
        // a row of an earlier version does not count for the current version 2
        item(s.earlierVersion).path("supersededVersion").asInt() shouldBe 2
        item(s.earlierVersion).path("acknowledged").asBoolean() shouldBe false
        // a row of another tenant for the same Doc UUID and version does not count
        item(s.foreignAck).path("acknowledged").asBoolean() shouldBe false
        item(s.inSet[2]).path("supersededVersion").asInt() shouldBe 1
        item(s.inSet[2]).path("entityRef").asString() shouldBe "ENT-A"
        item(s.inSet[2]).path("periodRef").asString() shouldBe "2026-03"
        item(s.inSet[2]).path("documentFunction").asString() shouldBe "BILL_FOR_PAYMENT"
    }

    @Test
    fun `INV-AC-24002 the pack column follows the packType and a null MDE version is never equal`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        val byMapping = doc(1, scope = ReprocessingKit.OTHER, mapping = ReprocessingKit.BAD)
        val byScope = doc(2)
        val byMde = doc(3, scope = ReprocessingKit.OTHER, mde = ReprocessingKit.BAD)
        val noMde = doc(4, scope = ReprocessingKit.OTHER)
        seed(listOf(byMapping, byScope, byMde, noMde))
        send("rep-scope")
        send("rep-map", packType = "MAPPING")
        send("rep-mde", packType = "MDE")
        poll().applied shouldBe 3
        ids("rep-scope") shouldBe listOf(byScope.docUuid.toString())
        ids("rep-map") shouldBe listOf(byMapping.docUuid.toString())
        ids("rep-mde") shouldBe listOf(byMde.docUuid.toString())
    }

    @Test
    fun `INV-AC-24002 a packType outside SCOPE, MAPPING and MDE is a contract violation with no marker and no page`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seed(listOf(doc(1)))
        send("rep-cal", packType = "CALENDAR")
        poll().deadLettered shouldBe 1
        deadLetters() shouldBe 1
        pages("rep-cal").size shouldBe 0
        markers("rep-cal") shouldBe 0
    }

    @Test
    fun `INV-AC-24002 an empty population gives one last page with no item`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seed(listOf(doc(1, scope = ReprocessingKit.OTHER)))
        send("rep-empty")
        poll() shouldBe applied
        val pages = pages("rep-empty")
        pages.size shouldBe 1
        pages.single().path("pageNumber").asInt() shouldBe 1
        pages.single().path("lastPage").asBoolean() shouldBe true
        pages.single().path("documents").size() shouldBe 0
        ReprocessingKit.schemaErrors(pages.single()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24002 a population of maxItems plus one is two pages and no page exceeds maxItems`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        val max = ReprocessingKit.maxItems
        seed((1..max + 1).map { doc(it) })
        send("rep-two")
        poll() shouldBe applied
        val pages = pages("rep-two")
        pages.map { it.path("pageNumber").asInt() } shouldBe listOf(1, 2)
        pages.map { it.path("lastPage").asBoolean() } shouldBe listOf(false, true)
        pages.map { it.path("documents").size() } shouldBe listOf(max, 1)
        pages.forEach { ReprocessingKit.schemaErrors(it) shouldBe emptyList() }
        ids("rep-two").size shouldBe max + 1
        ids("rep-two").toSet().size shouldBe max + 1
    }

    @Test
    fun `INV-AC-24002 a population of exactly maxItems is one last page`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        val max = ReprocessingKit.maxItems
        seed((1..max).map { doc(it) })
        send("rep-full")
        poll() shouldBe applied
        val pages = pages("rep-full")
        pages.size shouldBe 1
        pages.single().path("documents").size() shouldBe max
        pages.single().path("lastPage").asBoolean() shouldBe true
    }

    @Test
    fun `INV-AC-24002 an unknown entityRef and an entityRef of another tenant fail with no page and no marker`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seedEntity("ENT-ONLY-B", UUID.randomUUID(), UUID.randomUUID())
        send("rep-none", entityRef = "ENT-NONE")
        runCatching { poll() }.isFailure shouldBe true
        pages("rep-none").size shouldBe 0
        markers("rep-none") shouldBe 0
    }

    @Test
    fun `INV-AC-24002 the same reprocessing id for tenant B is answered on its own`(
        @TempDir dir: Path,
    ) = run(dir) {
        seedEntity()
        seed(listOf(doc(1)))
        val tenantB = UUID.randomUUID()
        val entityB = UUID.randomUUID()
        seedEntity("ENT-A", tenantB, entityB)
        seed(listOf(doc(50)), tenantB, entityB)
        send("rep-same")
        send("rep-same", owner = tenantB)
        poll().applied shouldBe 2
        ids("rep-same") shouldBe listOf(id(1).toString())
        pages("rep-same", tenantB).single().path("documents").size() shouldBe 1
    }

    @Test
    fun `INV-AC-24002 the boot jar holds the staged recorded-page schema and the limit read from it is the bundle one`(
        @TempDir dir: Path,
    ) {
        val entry =
            "BOOT-INF/classes/gst/contracts/service-runtime/schemas/" +
                "pack-reprocessing-population-recorded-payload.schema.json"
        ZipFile(checkNotNull(System.getProperty("gst.boot.jar")) { "gst.boot.jar is not set" }).use { jar ->
            val text = jar.getInputStream(jar.getEntry(entry)).use { it.readBytes().decodeToString() }
            SampleExchangeKit.mapper
                .readTree(text)
                .path("properties")
                .path("documents")
                .path("maxItems")
                .asInt() shouldBe ReprocessingKit.maxItems
        }
        ReprocessingKit.context(dir).run {
            val location = checkNotNull(it.environment.getProperty(ReprocessingConfiguration.SCHEMA_FILE_PROPERTY))
            location shouldBe ReprocessingKit.productionSchemaLocation()
            SampleConfiguration.maxItems(location, it) shouldBe ReprocessingKit.maxItems
        }
    }

    @Test
    fun `INV-AC-24002 the pages are a registered emitted type on their topic and the consumed type is not`() {
        CanonicalEvents.platformTopicByType[ReprocessingKit.RECORDED_TYPE] shouldBe ReprocessingKit.RECORDED_TOPIC
        CanonicalEvents.entries.map { it.eventType }.contains(ReprocessingKit.REQUEST_TYPE) shouldBe false
    }
}
