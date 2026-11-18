package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.ReconciliationDb
import com.dynokore.gst.reconciliation.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReadModel
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-24342 through the composed service (TPL-18, P109): the registry of the context holds the three consumers of
 * the run read model, and one CanonicalVersionCreated envelope is stored with its lines in a real PostgreSQL schema.
 */
class RunReadModelConsumerWiringTest {
    private val consumerNames =
        listOf("canonicalVersionCreatedConsumer", "periodReassignedConsumer", "submissionAcknowledgedConsumer")

    @Test
    fun `INV-AC-24342 the registry holds three subscriptions with their topics and the consumer name by equality`(
        @TempDir dir: Path,
    ) {
        ReconciliationContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val beans = context.getBeansOfType(InboxConsumer::class.java)
            beans.keys.filter { it in consumerNames }.sorted() shouldBe consumerNames.sorted()
            RunReadModelConsumerConfiguration.SUBSCRIPTIONS.map { it.topic } shouldContainExactly
                listOf(
                    "gst.canonical.canonical-version-created.v1",
                    "gst.canonical.period-reassigned.v1",
                    "gst.submission.submission-acknowledged.v1",
                )
            RunReadModelConsumerConfiguration.SUBSCRIPTIONS
                .map { it.topic }
                .toSet()
                .size shouldBe 3
            RunReadModelConsumerConfiguration.SUBSCRIPTIONS.map { it.consumer }.toSet() shouldBe
                setOf("reconciliation-svc")
            RunReadModelConsumerConfiguration.SUBSCRIPTIONS.map { it.schema }.toSet() shouldBe setOf("reconciliation")
        }
    }

    @Test
    fun `INV-AC-24342 the catalogue keeps its produced entries and lists the consumed ones with their producers`(
        @TempDir dir: Path,
    ) {
        ReconciliationContext.offline(dir).run { context ->
            val catalogue = context.getBean(EventCatalogue::class.java)
            val entries = catalogue.entries().toList()
            entries.size shouldBe ReconciliationEvents.entries.size + 4
            ReconciliationEvents.platformConsumedEntries.map { it.eventType to it.topic } shouldContainExactly
                listOf("com.dynokore.gst.platform.PolicyChanged.v1" to "gst.platform.policy-changed.v1")
            entries.containsAll(ReconciliationEvents.platformConsumedEntries) shouldBe true
            ReconciliationEvents.entries.forEach { produced -> entries.contains(produced) shouldBe true }
            ReconciliationEvents.topicByType.keys shouldBe
                setOf(
                    "com.dynokore.gst.reconciliation.GstReturnRecorded.v1",
                    "com.dynokore.gst.reconciliation.ReconciliationCompleted.v1",
                )
            ReconciliationEvents.consumedEntries.map { it.eventType to it.producers } shouldContainExactly
                listOf(
                    "com.dynokore.gst.canonical.CanonicalVersionCreated.v1" to setOf("canonical-svc"),
                    "com.dynokore.gst.canonical.PeriodReassigned.v1" to setOf("canonical-svc"),
                    "com.dynokore.gst.submission.SubmissionAcknowledged.v1" to setOf("submission-svc"),
                )
            ReconciliationEvents.consumedEntries.any { it.isProducedBy("reconciliation-svc") } shouldBe false
            ReconciliationEvents.consumedEntries.map { it.topic } shouldContainExactly
                RunReadModelConsumerConfiguration.SUBSCRIPTIONS.map { it.topic }
        }
    }

    @Test
    fun `INV-AC-24342 the three consumer beans are InboxConsumer typed so replayDelivery backs off explicitly`(
        @TempDir dir: Path,
    ) {
        // Replay is unavailable for this service until INV-T-24578 (platform-libs) makes replayDelivery serve several
        // InboxConsumer beans; svc-reconciliation takes it by a later pin bump (ruling of INV-T-24345, option A).
        ReconciliationContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val beans = context.getBeansOfType(InboxConsumer::class.java)
            beans.keys.count { it in consumerNames } shouldBe 3
            context.getBeansOfType(ReplayDelivery::class.java).size shouldBe 0
        }
        val methods =
            RunReadModelConsumerConfiguration::class.java.declaredMethods.filter { it.name in consumerNames }
        methods.size shouldBe 3
        methods.forEach { it.returnType shouldBe InboxConsumer::class.java }
    }

    @Test
    fun `INV-AC-24342 one CanonicalVersionCreated envelope is stored with its lines over PostgreSQL`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        val envelope = envelope(tenant, doc)
        ReconciliationContext.online(dir).run { context ->
            context.startupFailure shouldBe null
            val dsl = context.getBean(DSLContext::class.java)
            val handler = CanonicalVersionCreatedHandler(JooqRunReadModel(dsl))
            val scope = context.getBean(TenantScope::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val actor = "svc:reconciliation-svc"
            scope.bind(TenantId(tenant), actor) { tx.inTransaction { handler.handle(envelope) } } shouldBe
                HandleOutcome.APPLIED
            scope.bind(TenantId(tenant), actor) { tx.inTransaction { handler.handle(envelope) } } shouldBe
                HandleOutcome.IGNORED
            val documents =
                scope.bind(TenantId(tenant), actor) {
                    tx.inTransaction { JooqRunReadModel(dsl).load(tenant.toString(), "E1", "2026-Q1") }
                }
            documents.map { it.docUuid } shouldBe listOf(doc)
            val version = documents.single().versions.single()
            version.version shouldBe 1
            version.acknowledged shouldBe false
            storedLines(tenant, doc) shouldContainExactly
                listOf(
                    listOf(1, "SR", BigDecimal("100.00"), BigDecimal("9.00")),
                    listOf(2, "", BigDecimal("50.50"), BigDecimal("4.55")),
                )
        }
    }

    private fun envelope(
        tenant: UUID,
        doc: String,
    ): Envelope {
        val data =
            """{"docUuid":"$doc","version":1,"docsequence":3,"transactionDate":"2026-02-10",""" +
                """"prescribedPeriodRef":"2026-Q1","scopeVerdict":"SUBMITTABLE",""" +
                """"canonicalDocument":{"currency":{"value":"SGD","classification":"NONE"},""" +
                """"lines":{"value":[{"lineNumber":1,"lineAmount":"100.00","taxCode":"SR","taxAmount":"9.00"},""" +
                """{"lineNumber":2,"lineAmount":"50.50","taxAmount":"4.55"}],"classification":"NONE"}}}"""
        return Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/canonical-svc",
            type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            subject = doc,
            time = Instant.parse("2026-11-18T01:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E1"),
            docUuid = DocUuid(UUID.fromString(doc)),
            transactionDate = LocalDate.of(2026, 2, 10),
            docSequence = 3,
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )
    }

    private fun storedLines(
        tenant: UUID,
        doc: String,
    ): List<List<Any>> =
        ReconciliationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select line_number, tax_code, line_amount, tax_amount from reconciliation.document_line " +
                        "where tenant_id = ? and doc_uuid = ? order by line_number",
                ).use { st ->
                    st.setObject(1, tenant)
                    st.setObject(2, UUID.fromString(doc))
                    st.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                listOf(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4))
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }
}
