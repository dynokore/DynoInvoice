package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.OutboxCanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentAggregateRefs
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.application.document.AppliedPackVersion
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.document.SelfBillingAssignmentData
import com.dynokore.gst.canonical.application.document.SelfBillingSubmissionType
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID
import com.networknt.schema.SpecificationVersion as SchemaDialect

/**
 * The CanonicalVersionCreated outbox row against a real database (P109): the CanonicalVersionWriter over the jOOQ
 * store, the aggregate reference store and the OutboxWriter in the full TPL-18 context, as the `canonical_runtime`
 * login bound to a tenant (INV-REQ-439).
 */
class CanonicalVersionCreatedOutboxDbTest {
    private val actor = SignedBindings.ACTOR
    private val mapper = ObjectMapper()
    private val type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
    private val canonical =
        listOf(
            """{"documentTypeCode":{"value":"380","classification":"NONE"},"currency":{"value":"SGD","cla""",
            """ssification":"NONE"},"parties":{"value":{"supplier":{"identifierScheme":"UEN","identifierV""",
            """alue":"201912345A"},"buyer":{"identifierScheme":"UEN","identifierValue":"201912345A"}},"cl""",
            """assification":"IDENTIFYING"},"lines":{"value":[{"lineNumber":1,"description":"Consulting",""",
            """"quantity":1,"unitPrice":"100.00","lineAmount":"100.00","taxCode":"SR","taxAmount":"9.00"}""",
            """],"classification":"NONE"},"taxSubtotals":{"value":[{"taxCode":"SR","taxableAmount":"100.0""",
            """0","taxAmount":"9.00"}],"classification":"NONE"},"totals":{"value":{"totalExcludingTax":"1""",
            """00.00","totalTax":"9.00","totalIncludingTax":"109.00"},"classification":"NONE"}}""",
        ).joinToString("")

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
        val store = JooqDocumentStore(dsl)
        val refs = JooqDocumentAggregateRefs(dsl, scope.tenants)
        val publisher =
            OutboxCanonicalVersionCreatedPublisher(
                outbox,
                scope.tenants,
                { CorrelationId("corr-1") },
                { CausationId("cause-1") },
            )
        val writer = CanonicalVersionWriter(store, publisher, refs)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun newVersion(on: LocalDate = LocalDate.of(2026, 3, 20)) =
        NewVersion(canonical, ScopeVerdict.IN_SCOPE, "scope-1", "map-1", null, SpecificationVersion.PINT_SG, on, "svc")

    private fun newDocument(
        tenant: UUID,
        doc: UUID,
    ) = NewDocument(
        doc,
        tenant,
        UUID.randomUUID(),
        null,
        Direction.SALE,
        380,
        "N-$doc",
        "issuer",
        2026,
        null,
        DocumentState.READY,
        null,
        newVersion(),
    )

    private fun facts(
        doc: UUID,
        version: Int,
        aggregateId: String?,
        routing: RoutingFactsData = RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.ISSUED_VIA_NETWORK),
    ) = CanonicalVersionCreatedFacts(
        docUuid = doc,
        entityRef = "E-001",
        version = version,
        transactionDate = LocalDate.of(2026, 3, 20),
        prescribedPeriodRef = "2026-Q1",
        scopeVerdict = "SUBMITTABLE",
        appliedPackVersions = listOf(AppliedPackVersion("sg-gst", "1.0.0")),
        traceReference = "trace-$version",
        canonicalDocument = canonical,
        aggregateId = aggregateId,
        routingFacts = routing,
    )

    /** Reads as the canonical_runtime login bound to the tenant (row security applies). */
    private fun read(
        tenant: UUID,
        sql: String,
        vararg args: Any,
    ): List<List<String?>> =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
                    statement.executeQuery().use { rows ->
                        val width = rows.metaData.columnCount
                        buildList { while (rows.next()) add((1..width).map { rows.getString(it) }) }
                    }
                }
            } finally {
                connection.rollback()
            }
        }

    private fun rowsOf(
        tenant: UUID,
        doc: UUID,
    ) = read(
        tenant,
        "select docsequence::text, data::text from canonical.outbox where type = ? and doc_uuid = ?::uuid order by seq",
        type,
        doc.toString(),
    )

    private fun members(data: String): Map<String, String> =
        mapper.readTree(data).let { node ->
            node.propertyNames().asSequence().associateWith { node.get(it).toString() }
        }

    private fun aggregateOf(
        tenant: UUID,
        doc: UUID,
    ) = read(tenant, "select aggregate_id from canonical.document where doc_uuid = ?::uuid", doc.toString())
        .singleOrNull()
        ?.single()

    private fun seed(
        b: Boundary,
        tenant: UUID,
        d: UUID,
        e: UUID,
    ) {
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction {
                b.writer.createFirst(newDocument(tenant, d), facts(d, 1, null))
                b.refs.bind(d, "AGG-2026-0001")
                b.writer.createFirst(newDocument(tenant, e), facts(e, 1, null))
            }
        }
        // D's first row was staged before the bind; the aggregate is known from version 2 on, and stays unbound for E.
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction { b.writer.next(d, 1, newVersion(), facts(d, 2, null)) }
        }
    }

    @Test
    fun `INV-AC-22848 the row carries the nine members and aggregateId only when set, and the reference is stored once`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        val d = UUID.randomUUID()
        val e = UUID.randomUUID()
        seed(b, tenant, d, e)
        val dRows = rowsOf(tenant, d)
        dRows.size shouldBe 2
        dRows.forEach { row ->
            val m = members(row[1]!!)
            m.keys.containsAll(NINE) shouldBe true
            m["docsequence"] shouldBe row[0]
        }
        members(dRows[0][1]!!).containsKey("aggregateId") shouldBe false
        members(dRows[1][1]!!)["aggregateId"] shouldBe "\"AGG-2026-0001\""
        members(dRows[1][1]!!)["version"] shouldBe "2"
        members(rowsOf(tenant, e).single()[1]!!).containsKey("aggregateId") shouldBe false

        b.scope.bind(TenantId(tenant), actor) { b.runner.inTransaction { b.refs.bind(d, "AGG-OTHER") } }
        aggregateOf(tenant, d) shouldBe "AGG-2026-0001"
        aggregateOf(tenant, e) shouldBe null
        rowsOf(other, d).size shouldBe 0
        aggregateOf(other, d) shouldBe null
    }

    /** The published schema text of the pinned bundle; an unset property or a missing file fails the test. */
    private fun schemaText(): String {
        val dir = File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" })
        return File(dir, "schemas/canonical-version-created-payload.schema.json").readText()
    }

    private fun schemaErrors(data: String): List<String> {
        val registry =
            SchemaRegistry.withDefaultDialect(SchemaDialect.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry.getSchema(schemaText()).validate(mapper.readTree(data)).map { it.toString() }
    }

    /** The enum list of a `routingFacts` member as the published schema defines it; empty or absent fails. */
    private fun routingEnum(member: String): List<String> =
        mapper
            .readTree(schemaText())
            .get("properties")
            .get("routingFacts")
            .get("properties")
            .get(member)
            .get("enum")
            .values()
            .map { it.asString() }
            .also { it.shouldNotBeEmpty() }

    private fun noNullOrEmptyObject(node: JsonNode) {
        node.isNull shouldBe false
        if (node.isObject) {
            node.size() shouldNotBe 0
            node.properties().forEach { noNullOrEmptyObject(it.value) }
        }
    }

    /** Writes one version with [routing]; checks the row's routingFacts against the facts and the published schema. */
    private fun checkRouting(
        b: Boundary,
        tenant: UUID,
        routing: RoutingFactsData,
    ) {
        val d = UUID.randomUUID()
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction { b.writer.createFirst(newDocument(tenant, d), facts(d, 1, null, routing)) }
        }
        val data = rowsOf(tenant, d).single()[1]!!
        val row = mapper.readTree(data)
        row.get("routingFacts") shouldBe mapper.valueToTree<JsonNode>(expectedRouting(routing))
        noNullOrEmptyObject(row.get("routingFacts"))
        schemaErrors(data).shouldBeEmpty()
        val lower = row.deepCopy() as ObjectNode
        (lower.get("routingFacts") as ObjectNode).put("direction", routing.direction.name.lowercase())
        schemaErrors(lower.toString()).shouldNotBeEmpty()
    }

    /** The members the facts hold, written here independently of the publisher; the enum names as text. */
    private fun expectedRouting(routing: RoutingFactsData): Map<String, Any> =
        buildMap {
            put("direction", routing.direction.name)
            put("networkPath", routing.networkPath.name)
            routing.counterpartyOnNetwork?.let { put("counterpartyOnNetwork", it) }
            routing.debitNote?.let { put("debitNote", it) }
            routing.selfBillingAssignment?.let {
                put(
                    "selfBillingAssignment",
                    mapOf(
                        "direction" to it.direction.name,
                        "submissionType" to it.submissionType.name,
                    ),
                )
            }
        }

    @Test
    fun `INV-AC-23004 the three shapes of routing facts are written as given and validate`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val sale = RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.ISSUED_VIA_NETWORK)
        val selfBilling =
            RoutingFactsData(
                RoutingDirection.PURCHASE,
                RoutingNetworkPath.NOT_ON_NETWORK,
                selfBillingAssignment =
                    SelfBillingAssignmentData(RoutingDirection.PURCHASE, SelfBillingSubmissionType.TYPE_3),
            )
        val relay =
            RoutingFactsData(
                RoutingDirection.SALE,
                RoutingNetworkPath.RELAY_ISSUANCE,
                counterpartyOnNetwork = true,
                debitNote = true,
            )
        listOf(sale, selfBilling, relay).forEach { checkRouting(b, tenant, it) }
    }

    @Test
    fun `INV-AC-23004 every direction and every networkPath of the published schema is written and validates`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        routingEnum("direction").forEach {
            checkRouting(b, tenant, RoutingFactsData(RoutingDirection.valueOf(it), RoutingNetworkPath.NOT_ON_NETWORK))
        }
        routingEnum("networkPath").forEach {
            checkRouting(b, tenant, RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.valueOf(it)))
        }
    }

    @Test
    fun `INV-AC-22848 the topic of the type is catalogued and the row validates against the published schema`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        CanonicalEvents.topicByType[type] shouldBe "gst.canonical.canonical-version-created.v1"
        val tenant = UUID.randomUUID()
        val d = UUID.randomUUID()
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction { b.writer.createFirst(newDocument(tenant, d), facts(d, 1, "AGG-2026-0001")) }
        }
        val row = rowsOf(tenant, d).single()
        row[1]!!.let { schemaErrors(it).shouldBeEmpty() }
        val without =
            mapper.readTree(row[1]!!).also {
                (it as ObjectNode).remove("traceReference")
            }
        schemaErrors(without.toString()).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-22848 a version insert that fails stages nothing, caught or not`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val g = UUID.randomUUID()
        val failingStore =
            object : DocumentStore by b.store {
                override fun insert(document: NewDocument) = error("insert fails")
            }
        val writer = CanonicalVersionWriter(failingStore, b.publisher, b.refs)
        b.scope.bind(TenantId(tenant), actor) {
            b.runner.inTransaction {
                shouldThrow<IllegalStateException> { writer.createFirst(newDocument(tenant, g), facts(g, 1, null)) }
                // the caller swallows the failure and commits: nothing may have been staged before the insert
            }
        }
        rowsOf(tenant, g).size shouldBe 0
    }

    @Test
    fun `INV-AC-22848 a write that fails after the stage leaves no document row and no outbox row`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val tenant = UUID.randomUUID()
        val f = UUID.randomUUID()
        val failing =
            object : CanonicalVersionCreatedPublisher {
                override fun stage(facts: CanonicalVersionCreatedFacts) {
                    b.publisher.stage(facts)
                    error("write after the stage fails")
                }
            }
        val writer = CanonicalVersionWriter(b.store, failing, b.refs)
        shouldThrow<IllegalStateException> {
            b.scope.bind(TenantId(tenant), actor) {
                b.runner.inTransaction { writer.createFirst(newDocument(tenant, f), facts(f, 1, null)) }
            }
        }
        rowsOf(tenant, f).size shouldBe 0
        read(tenant, "select count(*) from canonical.document where doc_uuid = ?::uuid", f.toString()) shouldBe
            listOf(listOf("0"))
    }

    private companion object {
        val NINE =
            listOf(
                "docUuid",
                "version",
                "docsequence",
                "transactionDate",
                "prescribedPeriodRef",
                "scopeVerdict",
                "appliedPackVersions",
                "traceReference",
                "canonicalDocument",
            )
    }
}
