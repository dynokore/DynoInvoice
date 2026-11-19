package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.AggregateReadyHandler
import com.dynokore.gst.canonical.adapters.events.OutboxCanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentAggregateRefs
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.application.aggregate.AggregateDocumentJson
import com.dynokore.gst.canonical.application.aggregate.AggregateEntityIds
import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.Classified
import com.dynokore.gst.canonical.application.aggregate.LineClassifier
import com.dynokore.gst.canonical.application.aggregate.RecordAggregateDocument
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.document.AppliedPackVersion
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
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
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-22809 at the service boundary (P109): an AggregateReady event consumed by the real inbox consumer, the
 * use case, the jOOQ stores, the version writer and the outbox publisher of the TPL-18 context over PostgreSQL. The
 * rules and masterdata answers are fakes; the event-log read is a list in memory. The document that is no aggregate
 * is written through the same writer, as `recordDocument` still answers 501.
 */
class AggregateDocumentVersionEventDbTest {
    private val json = EnvelopeJson()
    private val mapper = JsonMapper.builder().build()
    private val aggregateId = "AGG-2026-0001"
    private val type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"

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

    private inner class Boundary(
        val context: ApplicationContext,
    ) {
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val writer: CanonicalVersionWriter = context.getBean(CanonicalVersionWriter::class.java)

        /** The same writer over the same stores with a fixed trace, for writes made outside an inbound event. */
        val directWriter =
            CanonicalVersionWriter(
                JooqDocumentStore(dsl),
                OutboxCanonicalVersionCreatedPublisher(
                    context.getBean(OutboxWriter::class.java),
                    scope.tenants,
                    { CorrelationId("corr-direct") },
                    { CausationId("cause-direct") },
                ),
                JooqDocumentAggregateRefs(dsl, scope.tenants),
            )
        val log = Log()
        val tenant: UUID = UUID.randomUUID()
        val entityId: UUID = UUID.randomUUID()
        val consumerName = AggregateReadyConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()

        private val classifier =
            LineClassifier { _, _, _, _ ->
                Classified("SR", "IN_SCOPE", mapOf("SCOPE" to "s1", "MAPPING" to "m1"), BigDecimal("9.00"))
            }
        private val supplierPort =
            AggregateSupplierIdentity { _, _, _ ->
                SupplierAnswer.Found(SupplierIdentity("0195:201912345A", "0195:201912345A", "201912345A"))
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
                        writer,
                    ),
                ),
                context.getBean(InboundEventTrace::class.java),
            )
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, AggregateReadyConsumerConfiguration.TOPIC, "canonical"),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                handler,
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )

        fun send(eventId: String) = log.add(envelope(tenant, eventId))

        fun aggregateDocuments() =
            rows("select doc_uuid::text, aggregate_id from canonical.document where tenant_id = '$tenant'")

        /** The version events of one document, in the order of their docsequence: (version, data). */
        fun events(doc: String) =
            rows(
                "select data::text from canonical.outbox where tenant_id = '$tenant' and type = '$type' " +
                    "and doc_uuid = '$doc'::uuid order by seq",
            ).map { mapper.readTree(it.single()) }

        fun eventCount() =
            rows("select count(*) from canonical.outbox where tenant_id = '$tenant' and type = '$type'")
                .single()
                .single()!!
                .toInt()
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

    private fun owner(sql: String) {
        CanonicalDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }
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
    ) = json.write(
        Envelope(
            id = EventId(eventId),
            source = "/gst/aggregation-svc",
            type = "com.dynokore.gst.aggregation.AggregateReady.v1",
            subject = aggregateId,
            time = Instant.parse("2026-11-12T10:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("ENT-1"),
            docUuid = DocUuid(UUID.randomUUID()),
            transactionDate = LocalDate.of(2026, 1, 31),
            docSequence = eventId.removePrefix("ev-").toLong(),
            instanceId = "aggregation-svc-0",
            correlation = CorrelationId("corr-$eventId"),
            causation = CausationId("cause-$eventId"),
            dataJson =
                """{"aggregateId":"$aggregateId","entityRef":"ENT-1","aggregateType":"POS",""" +
                    """"window":{"from":"2026-01-01","to":"2026-01-31","periodRef":"2026-01"},""" +
                    """"linesByInternalTaxCode":[{"internalTaxCode":"S1","amount":"100.00"}]}""",
        ),
    )

    private fun newVersion(canonical: String) =
        NewVersion(
            canonical,
            ScopeVerdict.IN_SCOPE,
            "scope-1",
            "map-1",
            null,
            SpecificationVersion.PINT_SG,
            LocalDate.of(2026, 1, 31),
            "svc",
        )

    private fun facts(
        doc: UUID,
        version: Int,
        canonical: String,
    ) = CanonicalVersionCreatedFacts(
        docUuid = doc,
        entityRef = "ENT-1",
        version = version,
        transactionDate = LocalDate.of(2026, 1, 31),
        prescribedPeriodRef = "2026-01",
        scopeVerdict = "IN_SCOPE",
        appliedPackVersions = listOf(AppliedPackVersion("SCOPE", "s1")),
        traceReference = "trace-$doc:$version",
        canonicalDocument = canonical,
        aggregateId = null,
        routingFacts = RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.NOT_ON_NETWORK),
    )

    @Test
    fun `INV-AC-22809 aggregate reference in the version events`(
        @TempDir dir: Path,
    ) = run(dir) {
        owner(
            "insert into canonical.entity_identity (tenant_id, entity_ref, entity_id, version) " +
                "values ('$tenant', 'ENT-1', '$entityId', 1)",
        )
        send("ev-1")
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        val aggregateDoc = aggregateDocuments().single()
        aggregateDoc[1] shouldBe aggregateId
        val first = events(aggregateDoc[0]!!).single()
        first["aggregateId"].asString() shouldBe aggregateId
        first["docUuid"].asString() shouldBe aggregateDoc[0]

        // the document that is no aggregate, through the same writer
        val plain = UUID.randomUUID()
        val canonical =
            rows("select canonical::text from canonical.document_version where tenant_id = '$tenant'")
                .single()
                .single()!!
        scope.bind(TenantId(tenant), SignedBindings.ACTOR) {
            runner.inTransaction {
                directWriter.createFirst(
                    NewDocument(
                        plain,
                        tenant,
                        entityId,
                        null,
                        Direction.SALE,
                        380,
                        "N-$plain",
                        "issuer",
                        2026,
                        null,
                        DocumentState.READY,
                        null,
                        newVersion(canonical),
                    ),
                    facts(plain, 1, canonical),
                )
            }
        }
        val plainEvent = events(plain.toString()).single()
        plainEvent.has("aggregateId") shouldBe false
        rows("select aggregate_id from canonical.document where doc_uuid = '$plain'::uuid")
            .single()
            .single() shouldBe null

        // a redelivery of the AggregateReady event (new event id, same aggregate)
        send("ev-2")
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        aggregateDocuments().size shouldBe 2
        eventCount() shouldBe 2

        // a later version of the aggregate document, supplied without the reference, carries the stored one
        val aggregateUuid = UUID.fromString(aggregateDoc[0])
        scope.bind(TenantId(tenant), SignedBindings.ACTOR) {
            runner.inTransaction {
                directWriter.next(aggregateUuid, 1, newVersion(canonical), facts(aggregateUuid, 2, canonical))
            }
        }
        val later = events(aggregateDoc[0]!!)
        later.size shouldBe 2
        later[1]["version"].asInt() shouldBe 2
        later[1]["aggregateId"].asString() shouldBe aggregateId
    }
}
