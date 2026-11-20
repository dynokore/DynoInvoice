package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.events.CapturingNetDerivedVersions
import com.dynokore.gst.canonical.adapters.events.JacksonNetDerivedVersions
import com.dynokore.gst.canonical.adapters.events.OutboxCanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.adapters.events.OutboxNetDerivationRefusedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentAggregateRefs
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.adapters.persistence.JooqNetDerivationDocuments
import com.dynokore.gst.canonical.adapters.persistence.JooqNetDerivationRecords
import com.dynokore.gst.canonical.adapters.persistence.JooqProcessedNetDerivationRequests
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.NetDerivationCreditNoteFact
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.netting.NetDerivationRequestHandler
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The wiring and the data of the net derivation database tests (INV-REQ-558 behavior 6): the application handler over
 * the jOOQ records, the single version writer and the outbox publishers, as the `canonical_runtime` login bound to a
 * tenant.
 */
open class NetDerivationHarness {
    val actor = SignedBindings.ACTOR
    val tenants = TenantContextImpl()
    val transaction =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(CanonicalDb.jdbcUrl)
                user = CanonicalDb.RUNTIME
                password = CanonicalDb.RUNTIME_PASSWORD
            },
            tenants,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    val declared =
        listOf(
            "document",
            "document_version",
            "document_line",
            "document_subtotal",
            "document_party",
            "inbound_event",
            "period_span",
            "document_version_net_derivation",
            "processed_net_derivation_request",
            "outbox",
            "outbox_sequence",
        ).associate { DSL.name("canonical", it) to TableClass.TENANT }
    val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(declared),
                tenants,
                transaction,
            ).dsl()
    val catalogue =
        EventCatalogue(
            listOf(
                CatalogueEntry(CREATED, "gst.canonical.canonical-version-created.v1", setOf(SERVICE), false, false),
                CatalogueEntry(REFUSED, "gst.canonical.net-derivation-refused.v1", setOf(SERVICE), false, false),
            ),
        )
    val corr = { CorrelationId("corr-1") }
    val cause = { CausationId("cause-1") }
    val outbox = JdbcOutboxWriter(dsl, "canonical", SERVICE, "canonical-svc-0", catalogue, Clock.systemUTC())
    val store = JooqDocumentStore(dsl)
    val documents = JooqNetDerivationDocuments(dsl, tenants)
    val records = JooqNetDerivationRecords(dsl, tenants)
    val versions =
        CapturingNetDerivedVersions(
            JacksonNetDerivedVersions { doc, version ->
                val identity = checkNotNull(tenants.currentActor())
                checkNotNull(documents.versionAsBase(doc, version, identity)) { "unknown $doc" }
            },
        )

    fun handler(entity: String = ENTITY_REF): NetDerivationRequestHandler {
        val writer =
            CanonicalVersionWriter(
                store,
                OutboxCanonicalVersionCreatedPublisher(outbox, tenants, corr, cause),
                JooqDocumentAggregateRefs(dsl, tenants),
            )
        return NetDerivationRequestHandler(
            requests = JooqProcessedNetDerivationRequests(dsl, tenants),
            documents = documents,
            versions = versions,
            writer = writer,
            records = records,
            refusals =
                OutboxNetDerivationRefusedPublisher(outbox, tenants, { EntityRef(entity) }, corr, cause),
            factsFor = { doc, net -> facts(doc, net) },
        )
    }

    fun facts(
        doc: UUID,
        net: NetDerivationFact,
    ): CanonicalVersionCreatedFacts {
        val basis = checkNotNull(documents.eventBasisOf(doc, net.baseVersion))
        return CanonicalVersionCreatedFacts(
            docUuid = doc,
            entityRef = basis.entityRef,
            version = net.baseVersion + 1,
            transactionDate = checkNotNull(documents.transactionDateOf(doc)),
            prescribedPeriodRef = basis.prescribedPeriodRef,
            scopeVerdict = basis.scopeVerdict,
            appliedPackVersions = checkNotNull(documents.appliedPacksOf(doc, net.baseVersion)),
            traceReference = "trace/$doc/v${net.baseVersion + 1}",
            canonicalDocument = versions.takeCanonical(),
            aggregateId = null,
            routingFacts =
                RoutingFactsData(RoutingDirection.valueOf(basis.direction), RoutingNetworkPath.NOT_ON_NETWORK),
            netDerivation = net,
        )
    }

    // ---- data ----

    fun canonical(
        number: String,
        amount: String,
        tax: String,
        gross: String,
        preceding: String? = null,
    ) = """{"documentTypeCode":{"value":"380","classification":"NONE"},""" +
        """"currency":{"value":"SGD","classification":"NONE"},""" +
        """"documentNumber":{"value":"$number","classification":"NONE"},""" +
        preceding?.let { """"precedingDocumentNumber":{"value":"$it","classification":"NONE"},""" }.orEmpty() +
        """"parties":{"value":{"supplier":{"identifierScheme":"UEN","identifierValue":"201912345A"},""" +
        """"buyer":{"identifierScheme":"UEN","identifierValue":"201912345B"}},"classification":"IDENTIFYING"},""" +
        """"lines":{"value":[{"lineNumber":1,"description":"Consulting","quantity":1,"unitPrice":"$amount",""" +
        """"lineAmount":"$amount","taxCode":"SR","taxAmount":"$tax"}],"classification":"NONE"},""" +
        """"taxSubtotals":{"value":[{"taxCode":"SR","taxableAmount":"$amount","taxAmount":"$tax"}],""" +
        """"classification":"NONE"},""" +
        """"totals":{"value":{"totalExcludingTax":"$amount","totalTax":"$tax","totalIncludingTax":"$gross"},""" +
        """"classification":"NONE"}}"""

    /** The scope, mapping and MDE pack versions the seeded rows store; a test sets them before it seeds. */
    var packs: Triple<String?, String?, String?> = Triple("scope-1", "map-1", null)

    fun version(canonical: String) =
        NewVersion(
            canonical,
            ScopeVerdict.IN_SCOPE,
            packs.first,
            packs.second,
            packs.third,
            SpecificationVersion.PINT_SG,
            DAY,
            "svc:seed",
        )

    inner class Seed(
        val tenant: UUID = UUID.randomUUID(),
        val entity: UUID = UUID.randomUUID(),
        val invoice: UUID = UUID.randomUUID(),
        val note: UUID = UUID.randomUUID(),
    ) {
        val periodId: UUID = UUID.randomUUID()

        fun run(block: () -> Unit) = inTenant(tenant) { block() }
    }

    /** A purchase invoice of [versions] versions (9000.00) and a purchase credit note (1000.00) that names it. */
    fun seed(versions: Int = 1): Seed =
        Seed().also { s ->
            inTenant(s.tenant) {
                dsl
                    .insertInto(PERIOD_SPAN)
                    .set(PERIOD_SPAN.TENANT_ID, s.tenant)
                    .set(PERIOD_SPAN.ENTITY_REF, ENTITY_REF)
                    .set(PERIOD_SPAN.PERIOD_REF, "2026-Q1")
                    .set(PERIOD_SPAN.PERIOD_START, LocalDate.of(2026, 1, 1))
                    .set(PERIOD_SPAN.PERIOD_END, LocalDate.of(2026, 3, 31))
                    .set(PERIOD_SPAN.PERIOD_ID, s.periodId)
                    .execute()
                val number = "INV-" + s.invoice
                store.insert(document(s, s.invoice, number, canonical(number, "9000.00", "810.00", "9810.00")))
                (2..versions).forEach { v ->
                    store.createVersion(s.invoice, v - 1, version(canonical(number, "9000.00", "810.00", "9810.00")))
                }
                (1..versions).forEach { v -> inbound(s, s.invoice, v, "INVOICE") }
                val noteNumber = "CN-" + s.note
                store.insert(
                    document(s, s.note, noteNumber, canonical(noteNumber, "1000.00", "90.00", "1090.00", number)),
                )
                inbound(s, s.note, 1, "CREDIT_NOTE")
            }
        }

    fun document(
        s: Seed,
        doc: UUID,
        number: String,
        canonical: String,
    ) = NewDocument(
        doc,
        s.tenant,
        s.entity,
        null,
        Direction.PURCHASE,
        380,
        number,
        "issuer-1",
        2026,
        null,
        DocumentState.READY,
        s.periodId,
        version(canonical),
    )

    fun inbound(
        s: Seed,
        doc: UUID,
        version: Int,
        function: String,
    ) {
        dsl
            .insertInto(INBOUND_EVENT)
            .set(INBOUND_EVENT.TENANT_ID, s.tenant)
            .set(INBOUND_EVENT.ENTITY_ID, s.entity)
            .set(INBOUND_EVENT.DOC_UUID, doc)
            .set(INBOUND_EVENT.VERSION, version)
            .set(INBOUND_EVENT.EVENT_KIND, "POSTING")
            .set(INBOUND_EVENT.DOCUMENT_FUNCTION, function)
            .execute()
    }

    fun <T> inTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), actor) { transaction.inTransaction { block() } }

    fun handle(
        s: Seed,
        eventId: UUID,
        base: Int,
        h: NetDerivationRequestHandler = handler(),
    ) = inTenant(s.tenant) { h.handle(eventId, s.invoice, base, listOf(NetDerivationCreditNoteFact(s.note, 1))) }

    // ---- reads as the runtime login bound to a tenant (row security applies) ----

    fun read(
        tenant: UUID,
        sql: String,
        vararg args: Any,
    ): List<List<String?>> =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg.toString()) }
                    statement.executeQuery().use { rows ->
                        val width = rows.metaData.columnCount
                        buildList { while (rows.next()) add((1..width).map { rows.getString(it) }) }
                    }
                }
            } finally {
                connection.rollback()
            }
        }

    fun versionCount(s: Seed) =
        read(s.tenant, "select count(*) from canonical.document_version where doc_uuid = ?::uuid", s.invoice.toString())
            .single()
            .single()!!
            .toInt()

    fun recordRows(s: Seed) =
        read(
            s.tenant,
            "select count(*) from canonical.document_version_net_derivation where doc_uuid = ?::uuid",
            s.invoice,
        ).single()
            .single()!!
            .toInt()

    fun outboxData(
        s: Seed,
        type: String,
    ): List<String> =
        read(
            s.tenant,
            "select data::text from canonical.outbox where type = ? and doc_uuid = ?::uuid order by seq",
            type,
            s.invoice,
        ).map { it[0]!! }

    fun payload(text: String): JsonNode = NetContractSchemas.mapper.readTree(text)

    val createdSchema get() = NetContractSchemas.canonicalSchemaText(CREATED_SCHEMA)
    val refusedSchema get() = NetContractSchemas.canonicalSchemaText(REFUSED_SCHEMA)

    companion object {
        const val SERVICE = "canonical-svc"
        const val CREATED_SCHEMA = "canonical-version-created-payload.schema.json"
        const val REFUSED_SCHEMA = "net-derivation-refused-payload.schema.json"
        const val ENTITY_REF = "E-001"
        const val CREATED = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
        const val REFUSED = "com.dynokore.gst.canonical.NetDerivationRefused.v1"
        val DAY: LocalDate = LocalDate.of(2026, 3, 20)
    }
}

/**
 * INV-REQ-558 behavior 6: the net derivation adapters against a real database, as the `canonical_runtime` login bound
 * to a tenant (wiring and data: [NetDerivationHarness]).
 */
class NetDerivationDbTest : NetDerivationHarness() {
    @Test
    fun `INV-AC-25342 the first handling stores one version, one record and one event row with netDerivation`() {
        val s = seed(versions = 2)
        val event = UUID.randomUUID()
        handle(s, event, base = 2)
        versionCount(s) shouldBe 3
        recordRows(s) shouldBe 1
        val rows = outboxData(s, CREATED)
        // the two versions that the seed wrote are not announced: only the derived version is
        rows.size shouldBe 1
        NetContractSchemas.errors(createdSchema, rows.single()).shouldBeEmpty()
        val net = payload(rows.single()).get("netDerivation")
        net.get("kind").asString() shouldBe "SYSTEM_DERIVED"
        net.get("baseVersion").asInt() shouldBe 2
        net.get("creditNotes").toString() shouldBe """[{"docUuid":"${s.note}","version":1}]"""
        // netDerivation as stored: the row of the table
        inTenant(s.tenant) {
            val stored = records.recordOf(s.invoice, 3)!!
            stored.baseVersion shouldBe 2
            stored.creditNotes.map { it.docUuid to it.version } shouldBe listOf(s.note to 1)
        }
        payload(rows.single()).get("version").asInt() shouldBe 3
        outboxData(s, REFUSED).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-25342 the second handling of the same request stores nothing more`() {
        val s = seed(versions = 2)
        val event = UUID.randomUUID()
        handle(s, event, base = 2)
        handle(s, event, base = 2)
        versionCount(s) shouldBe 3
        recordRows(s) shouldBe 1
        outboxData(s, CREATED).size shouldBe 1
        outboxData(s, REFUSED).size shouldBe 0
    }

    @Test
    fun `INV-AC-25342 a refusal row validates against the refused schema and carries the request event id`() {
        val s = seed(versions = 2)
        val event = UUID.randomUUID()
        handle(s, event, base = 1) // the original is at version 2: BASE_CHANGED
        versionCount(s) shouldBe 2
        recordRows(s) shouldBe 0
        outboxData(s, CREATED).shouldBeEmpty()
        val row = outboxData(s, REFUSED).single()
        NetContractSchemas.errors(refusedSchema, row).shouldBeEmpty()
        payload(row).get("requestEventId").asString() shouldBe event.toString()
        payload(row).get("reason").asString() shouldBe "BASE_CHANGED"
        payload(row).get("originalDocUuid").asString() shouldBe s.invoice.toString()
        payload(row).get("creditNotes").toString() shouldBe """[{"docUuid":"${s.note}","version":1}]"""
    }

    @Test
    fun `INV-AC-25342 a request of another tenant for the same Doc UUID is not handled`() {
        val s = seed()
        val other = UUID.randomUUID()
        val event = UUID.randomUUID()
        inTenant(other) {
            handler().handle(event, s.invoice, 1, listOf(NetDerivationCreditNoteFact(s.note, 1)))
        }
        versionCount(s) shouldBe 1
        recordRows(s) shouldBe 0
        outboxData(s, CREATED).shouldBeEmpty()
        // the other tenant is answered with a refusal of its own, which the owner of the document never sees
        outboxData(s, REFUSED).shouldBeEmpty()
        read(other, "select data::text from canonical.outbox where type = ?", REFUSED).single()[0]!!.let {
            payload(it).get("reason").asString() shouldBe "DOCUMENT_NOT_FOUND"
        }
    }

    @Test
    fun `INV-AC-25342 recordOf returns the stored base version and credit notes, null for the base version`() {
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        inTenant(s.tenant) {
            val derived = records.recordOf(s.invoice, 2)!!
            derived.baseVersion shouldBe 1
            derived.creditNotes shouldBe listOf(NetDerivationCreditNoteFact(s.note, 1))
            records.recordOf(s.invoice, 1) shouldBe null
            records.recordOf(s.invoice, 3) shouldBe null
            records.recordOf(UUID.randomUUID(), 2) shouldBe null
        }
        inTenant(UUID.randomUUID()) { records.recordOf(s.invoice, 2) shouldBe null }
    }

    @Test
    fun `INV-AC-25342 a failure after the staging rolls back the version, the record, the mark and the outbox row`() {
        val s = seed()
        val event = UUID.randomUUID()
        val failing =
            object : com.dynokore.gst.canonical.application.netting.NetDerivationRecords by records {
                override fun record(
                    docUuid: UUID,
                    version: Int,
                    baseVersion: Int,
                    creditNotes: List<com.dynokore.gst.canonical.application.netting.NetCreditNoteRef>,
                ) {
                    records.record(docUuid, version, baseVersion, creditNotes)
                    error("boom after the record")
                }
            }
        val base = handler()
        val broken =
            NetDerivationRequestHandler(
                JooqProcessedNetDerivationRequests(dsl, tenants),
                documents,
                versions,
                CanonicalVersionWriter(
                    store,
                    OutboxCanonicalVersionCreatedPublisher(outbox, tenants, corr, cause),
                    JooqDocumentAggregateRefs(dsl, tenants),
                ),
                failing,
                OutboxNetDerivationRefusedPublisher(outbox, tenants, { EntityRef(ENTITY_REF) }, corr, cause),
            ) { doc, net -> facts(doc, net) }
        shouldThrow<IllegalStateException> { handle(s, event, base = 1, h = broken) }
        versionCount(s) shouldBe 1
        recordRows(s) shouldBe 0
        outboxData(s, CREATED).shouldBeEmpty()
        read(
            s.tenant,
            "select count(*) from canonical.processed_net_derivation_request where request_event_id = ?::uuid",
            event,
        ).single()
            .single() shouldBe "0"
        // the same request is handled once the failure is gone: the mark was rolled back with the rest
        handle(s, event, base = 1, h = base)
        versionCount(s) shouldBe 2
        outboxData(s, CREATED).size shouldBe 1
    }

    @Test
    fun `INV-AC-25342 a stored netDerivation row is never edited by the runtime login`() {
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, s.tenant)
            shouldThrow<java.sql.SQLException> {
                connection.createStatement().use {
                    it.executeUpdate("update canonical.document_version_net_derivation set base_version = 1")
                }
            }
            connection.rollback()
        }
    }

    private fun JsonNode.excludingTax(): String = get("totals").get("value").get("totalExcludingTax").asString()

    @Test
    fun `INV-AC-2786 the request stores version 2 of 8000 with its netDerivation and exactly 2 versions exist`() {
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        versionCount(s) shouldBe 2
        val sql = "select canonical::text from canonical.document_version where doc_uuid = ?::uuid and version = 2"
        payload(read(s.tenant, sql, s.invoice).single().single()!!).excludingTax() shouldBe "8000.00"
        inTenant(s.tenant) {
            val stored = records.recordOf(s.invoice, 2)!!
            stored.kind.name shouldBe "SYSTEM_DERIVED"
            stored.baseVersion shouldBe 1
            stored.nettedCreditNoteDocUuids shouldBe listOf(s.note)
        }
        // the acting identity of the written version is the service identity the consumer is bound to
        val by = "select created_by from canonical.document_version where doc_uuid = ?::uuid and version = 2"
        read(s.tenant, by, s.invoice).single().single() shouldBe actor
    }

    @Test
    fun `INV-AC-2786 exactly one CanonicalVersionCreated for version 2 validates and carries the netDerivation`() {
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        val rows = outboxData(s, CREATED)
        rows.size shouldBe 1
        NetContractSchemas.errors(createdSchema, rows.single()).shouldBeEmpty()
        val event = payload(rows.single())
        event.get("version").asInt() shouldBe 2
        event.get("docUuid").asString() shouldBe s.invoice.toString()
        event.get("nettedCreditNoteDocUuids").toString() shouldBe """["${s.note}"]"""
        event.get("netDerivation").toString() shouldBe
            """{"kind":"SYSTEM_DERIVED","baseVersion":1,"creditNotes":[{"docUuid":"${s.note}","version":1}]}"""
        event.get("canonicalDocument").excludingTax() shouldBe "8000.00"
    }

    private fun packsOfEvent(s: Seed): String =
        payload(outboxData(s, CREATED).single())
            .get("appliedPackVersions")
            .joinToString(",") { it.get("packIdentifier").asString() + "=" + it.get("version").asString() }

    private fun packColumns(
        s: Seed,
        version: Int,
    ): List<String?> =
        read(
            s.tenant,
            "select scope_pack_version, mapping_pack_version, mde_pack_version from canonical.document_version " +
                "where doc_uuid = ?::uuid and version = $version",
            s.invoice,
        ).single()

    @Test
    fun `INV-AC-25342 appliedPackVersions equals the base columns for a base with an MDE version`() {
        packs = Triple("1.0.0", "2.0.0", "3.0.0")
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        packsOfEvent(s) shouldBe "SCOPE=1.0.0,MAPPING=2.0.0,MDE=3.0.0"
        NetContractSchemas.errors(createdSchema, outboxData(s, CREATED).single()).shouldBeEmpty()
        packColumns(s, 2) shouldBe listOf("1.0.0", "2.0.0", "3.0.0")
    }

    @Test
    fun `INV-AC-25342 appliedPackVersions has two members and no MDE for a base without an MDE version`() {
        packs = Triple("1.0.0", "2.0.0", null)
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        packsOfEvent(s) shouldBe "SCOPE=1.0.0,MAPPING=2.0.0"
        packColumns(s, 2) shouldBe packColumns(s, 1)
        packColumns(s, 2) shouldBe listOf("1.0.0", "2.0.0", null)
    }

    @Test
    fun `INV-AC-25342 scope 1_0_0 and mapping 2_0_0 are not swapped in the event or the derived row`() {
        packs = Triple("1.0.0", "2.0.0", null)
        val s = seed()
        handle(s, UUID.randomUUID(), base = 1)
        val members = payload(outboxData(s, CREATED).single()).get("appliedPackVersions")
        members.get(0).get("packIdentifier").asString() shouldBe "SCOPE"
        members.get(0).get("version").asString() shouldBe "1.0.0"
        members.get(1).get("packIdentifier").asString() shouldBe "MAPPING"
        members.get(1).get("version").asString() shouldBe "2.0.0"
        packColumns(s, 2)[0] shouldBe "1.0.0"
        packColumns(s, 2)[1] shouldBe "2.0.0"
    }

    @Test
    fun `INV-AC-25342 a base with a null mapping pack stores nothing, stages nothing and leaves the mark unset`() {
        packs = Triple("1.0.0", null, null)
        val s = seed()
        val event = UUID.randomUUID()
        shouldThrow<IllegalStateException> { handle(s, event, base = 1) }
        versionCount(s) shouldBe 1
        recordRows(s) shouldBe 0
        outboxData(s, CREATED).shouldBeEmpty()
        read(
            s.tenant,
            "select count(*) from canonical.processed_net_derivation_request where request_event_id = ?::uuid",
            event,
        ).single()
            .single() shouldBe "0"
    }
}
