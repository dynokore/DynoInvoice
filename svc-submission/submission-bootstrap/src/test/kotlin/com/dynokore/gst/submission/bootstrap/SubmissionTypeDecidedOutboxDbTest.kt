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
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `SubmissionTypeDecided` through the wired composite handler, the real register store and the real outbox, in one
 * transaction (INV-AC-22930).
 */
class SubmissionTypeDecidedOutboxDbTest {
    private val type = "com.dynokore.gst.submission.SubmissionTypeDecided.v1"
    private val topic = "gst.submission.submission-type-decided.v1"
    private val period = "2026-Q1"
    private val json = JsonMapper.builder().build()

    private inner class Fixture(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val handler: EventHandler = context.getBean("canonicalVersionCreatedHandler", EventHandler::class.java)
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

    @Suppress("UNCHECKED_CAST")
    private fun payloadOf(data: String): MutableMap<String, Any?> =
        json.readValue(data, MutableMap::class.java) as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun payload(row: OutboxRecord): Map<String, Any?> =
        json.readValue(checkNotNull(row.data).data(), Map::class.java) as Map<String, Any?>

    @Test
    fun `INV-AC-22930 decided type is staged with the register write`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        val doc = UUID.randomUUID().toString()
        asTenant(a) { handler.handle(envelope(doc, 1)) } shouldBe HandleOutcome.APPLIED

        register(doc) shouldBe 1
        val rows = events(a, doc)
        rows.size shouldBe 1
        rows[0].topic shouldBe topic
        rows[0].subject shouldBe doc
        val data = payload(rows[0])
        data.keys shouldBe setOf("docUuid", "version", "docsequence", "submissionType")
        data["docUuid"] shouldBe doc
        data["version"] shouldBe 1
        data["submissionType"] shouldBe "TYPE_2"
        (data["docsequence"] as Number).toLong() shouldBe checkNotNull(rows[0].docsequence)
    }

    @Test
    fun `INV-AC-22930 failed event leaves no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        val doc = UUID.randomUUID().toString()
        runCatching {
            asTenant(a) {
                handler.handle(envelope(doc, 1))
                error("fails after the register write")
            }
        }.isFailure shouldBe true
        register(doc) shouldBe 0
        events(a, doc) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22930 other tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        val doc = UUID.randomUUID().toString()
        asTenant(a) { handler.handle(envelope(doc, 1)) } shouldBe HandleOutcome.APPLIED
        events(a, doc).size shouldBe 1
        events(b, doc) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22930 row validates against the pinned schema`(
        @TempDir dir: Path,
    ) = run(dir) {
        dueDate()
        val doc = UUID.randomUUID().toString()
        asTenant(a) { handler.handle(envelope(doc, 1)) } shouldBe HandleOutcome.APPLIED
        val data = checkNotNull(events(a, doc).single().data).data()

        fun with(change: (MutableMap<String, Any?>) -> Unit): String =
            json.writeValueAsString(payloadOf(data).also(change))

        violations(data) shouldBe emptyList()
        violations(with { it["submissionType"] = "TYPE_9" }).size shouldBe 1
        violations(with { it.remove("docsequence") }).size shouldBe 1
        violations(with { it["version"] = 0 }).size shouldBe 1
    }

    @Test
    fun `INV-AC-22930 catalogue maps the type to its topic`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).run { context ->
            SubmissionEvents.topicByType[type] shouldBe topic
            val entry = context.getBean(com.dynokore.platform.outboxandqueue.EventCatalogue::class.java).find(type)
            entry?.topic shouldBe topic
        }
    }

    /**
     * The violations of [data] against the pinned schema file: every `required` member is present, each named property
     * has its `type`, its `minimum` and its `enum`, and a `uuid` format parses. bootstrap has no JSON Schema library on
     * its test class path, so this reads the keywords the file uses.
     */
    private fun violations(data: String): List<String> {
        val schema = json.readTree(schemaFile().readText())
        val node = json.readTree(data)
        val out = mutableListOf<String>()
        schema.get("required").values().forEach {
            if (node.get(it.asString()) ==
                null
            ) {
                out += "missing ${it.asString()}"
            }
        }
        schema.get("properties").properties().forEach { (name, rule) ->
            val value = node.get(name) ?: return@forEach
            val typeOk =
                when (rule.get("type").asString()) {
                    "string" -> value.isString
                    "integer" -> value.isIntegralNumber
                    else -> false
                }
            if (!typeOk) out += "$name type"
            if (typeOk && rule.has("minimum") && value.asLong() < rule.get("minimum").asLong()) out += "$name minimum"
            if (typeOk && rule.has("enum") && rule.get("enum").values().none { it.asString() == value.asString() }) {
                out += "$name enum"
            }
            if (typeOk && rule.get("format")?.asString() == "uuid") {
                runCatching { UUID.fromString(value.asString()) }
                    .onFailure { out += "$name uuid" }
            }
        }
        return out
    }

    /** The pinned bundle staged by submission-contract (gst.contract.dir when set, else the module's build dir). */
    private fun schemaFile(): File {
        val name = "schemas/submission-type-decided-payload.schema.json"
        System.getProperty("gst.contract.dir")?.let { return File(it, name) }
        val base = File("../submission-contract/build/contracts-repo/platform-contracts/submission")
        return checkNotNull(base.listFiles()?.map { File(it, name) }?.singleOrNull { it.isFile }) {
            "the pinned submission contract bundle is not staged under $base"
        }
    }
}
