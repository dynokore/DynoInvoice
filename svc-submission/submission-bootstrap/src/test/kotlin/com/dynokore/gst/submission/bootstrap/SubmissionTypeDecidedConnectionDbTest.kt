package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.OutboxRecord
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `SubmissionTypeDecided` names the connection the entity is assigned to, read from the replica of
 * `AccessPointRouteChanged` in the same transaction as the decision (INV-AC-23662, INV-REQ-2467).
 */
class SubmissionTypeDecidedConnectionDbTest {
    private val type = "com.dynokore.gst.submission.SubmissionTypeDecided.v1"
    private val period = "2026-Q1"
    private val json = JsonMapper.builder().build()

    private inner class Fixture(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val handler: EventHandler = context.getBean("canonicalVersionCreatedHandler", EventHandler::class.java)
        val routes: EventHandler = context.getBean("accessPointRouteChangedHandler", EventHandler::class.java)
        val dueDates: PeriodDueDates = context.getBean(PeriodDueDates::class.java)
        val a: UUID = UUID.randomUUID()
        val b: UUID = UUID.randomUUID()

        fun <T> asTenant(
            tenant: UUID,
            block: () -> T,
        ): T = tenants.bind(TenantId(tenant), "svc:submission-svc") { runner.inTransaction(block) }

        fun dueDate() {
            asTenant(a) { dueDates.upsert(TenantId(a), "E-001", period, LocalDate.parse("2026-05-15"), 1) }
        }

        fun register(doc: String): Int =
            asTenant(a) {
                dsl
                    .selectFrom(ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY)
                    .where(ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY.DOC_UUID.eq(doc))
                    .fetch()
                    .size
            }

        fun events(
            tenant: UUID,
            doc: String,
        ): List<OutboxRecord> =
            asTenant(tenant) {
                dsl
                    .selectFrom(Outbox.OUTBOX)
                    .where(Outbox.OUTBOX.DOC_UUID.eq(UUID.fromString(doc)))
                    .and(Outbox.OUTBOX.TYPE.eq(type))
                    .orderBy(Outbox.OUTBOX.ID)
                    .fetch()
                    .toList()
            }

        fun route(
            connection: String,
            version: Long,
            vararg entities: String,
        ) {
            val list = entities.joinToString(",") { "\"$it\"" }
            val data = """{"connectionRef":"$connection","entityRefs":[$list],"version":$version}"""
            asTenant(a) { routes.handle(routeEnvelope(data)) }
        }

        fun routeEnvelope(data: String): Envelope =
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/transport-svc",
                type = "com.dynokore.gst.transport.AccessPointRouteChanged.v1",
                subject = "route",
                time = Instant.parse("2026-11-15T08:00:00Z"),
                tenant = TenantId(a),
                entity = EntityRef("E-001"),
                docUuid = null,
                transactionDate = null,
                docSequence = 1,
                instanceId = "transport-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = data,
            )

        /** Decides a new document of E-001 and returns the staged payload. */
        fun decide(): Map<String, Any?> {
            val doc = UUID.randomUUID().toString()
            asTenant(a) { handler.handle(envelope(doc, 1)) } shouldBe HandleOutcome.APPLIED
            return payload(events(a, doc).single())
        }

        fun envelope(
            doc: String,
            version: Int,
        ): Envelope =
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                subject = doc,
                time = Instant.parse("2026-11-15T08:00:00Z"),
                tenant = TenantId(a),
                entity = EntityRef("E-001"),
                docUuid = DocUuid(UUID.fromString(doc)),
                transactionDate = LocalDate.parse("2026-03-20"),
                docSequence = 1,
                instanceId = "canonical-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = created(doc, version),
            )
    }

    private fun created(
        doc: String,
        version: Int,
    ): String =
        """{"docUuid":"$doc","version":$version,"transactionDate":"2026-03-20","prescribedPeriodRef":"$period",
        |"scopeVerdict":"SUBMITTABLE",
        |"routingFacts":{"direction":"SALE","networkPath":"NOT_ON_NETWORK"},
        |"canonicalDocument":{"documentNumber":{"value":"INV-1"},"documentTypeCode":{"value":"380"},
        |"currency":{"value":"SGD"},
        |"parties":{"value":{"buyer":{"name":"Buyer","identifierScheme":"SG_UEN","identifierValue":"201912345A"}}},
        |"totals":{"value":{"totalExcludingTax":"100.00","totalTax":"9.00"}},
        |"lines":{"value":[{"taxCode":"SR"}]}}}
        """.trimMargin().replace("\n", "")

    private fun run(
        dir: Path,
        block: Fixture.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Fixture(context).block() }
    }

    @Test
    fun `INV-AC-23662 connectionRef of the assigned connection is in the payload`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 1, "E-001")
        val data = decide()
        data["connectionRef"] shouldBe "C"
        data["version"] shouldBe 1
        data["submissionType"] shouldBe "TYPE_2"
        data.keys shouldBe setOf("docUuid", "version", "docsequence", "submissionType", "connectionRef")
    }

    @Test
    fun `INV-AC-23662 an unassigned entity has no connectionRef member`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 1, "E-002")
        val data = decide()
        data.keys shouldBe setOf("docUuid", "version", "docsequence", "submissionType")
        data["submissionType"] shouldBe "TYPE_2"
    }

    @Test
    fun `INV-AC-23662 a move applies to later decisions only`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 4, "E-001")
        val before = decide()
        route("D", 3, "E-001")
        route("C", 5)
        val after = decide()
        route("C", 4, "E-001")
        val stale = decide()
        before["connectionRef"] shouldBe "C"
        after["connectionRef"] shouldBe "D"
        stale["connectionRef"] shouldBe "D"
        after["submissionType"] shouldBe before["submissionType"]
    }

    @Test
    fun `INV-AC-23662 a move delivered source first keeps the entity at the destination`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 4, "E-001")
        route("C", 5)
        route("D", 3, "E-001")
        decide()["connectionRef"] shouldBe "D"
    }

    @Test
    fun `INV-AC-23662 a move delivered destination first keeps the entity at the destination`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 4, "E-001")
        route("D", 3, "E-001")
        route("C", 5)
        decide()["connectionRef"] shouldBe "D"
    }

    @Test
    fun `INV-AC-23662 an older route version of the same connection is ignored`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 5, "E-001")
        route("C", 4)
        decide()["connectionRef"] shouldBe "C"
    }

    @Test
    fun `INV-AC-23662 a repeated version of the same connection is ignored`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 5, "E-001")
        route("C", 5)
        decide()["connectionRef"] shouldBe "C"
    }

    @Test
    fun `INV-AC-23662 an entity removed from the list is deleted`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 1, "E-001")
        route("C", 2)
        decide().containsKey("connectionRef") shouldBe false
    }

    @Test
    fun `INV-AC-23662 a version of another connection is not compared`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        route("C", 9, "E-001")
        route("D", 2, "E-001")
        decide()["connectionRef"] shouldBe "D"
    }

    @Suppress("UNCHECKED_CAST")
    private fun payload(row: OutboxRecord): Map<String, Any?> =
        json.readValue(checkNotNull(row.data).data(), Map::class.java) as Map<String, Any?>
}
