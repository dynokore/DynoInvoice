package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SignedBindings
import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.OutboxRecord
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
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry as Entry

/**
 * The submission half of the $9,000 invoice and $1,000 credit note netted into one $8,000 version (INV-AC-25366,
 * INV-REQ-558 behavior 6). The real `canonicalVersionCreatedHandler` and `payloadReadyHandler` beans run over
 * PostgreSQL; the event of the derived version is the one canonical-svc publishes, built from the same figures and
 * validated against the schema of the pinned platform-contracts bundle (consumer-driven contract testing). The outside
 * is a fake: no canonical-svc code, no network.
 */
class NetSubmissionScenarioDbTest {
    private val actor = "svc:submission-svc"
    private val period = "2026-Q1"
    private val json = JsonMapper.builder().build()
    private val requestType = "com.dynokore.gst.submission.NetDerivationRequested.v1"
    private val bytes = "<Invoice/>".toByteArray()

    private inner class Kit(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val canonical: EventHandler = context.getBean("canonicalVersionCreatedHandler", EventHandler::class.java)
        val payloadReady: EventHandler = context.getBean("payloadReadyHandler", EventHandler::class.java)
        val tenant: UUID = UUID.randomUUID()
        val original: String = UUID.randomUUID().toString()
        val creditNote: String = UUID.randomUUID().toString()

        init {
            seedDueDate()
        }

        fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

        private fun seedDueDate() {
            val due = LocalDate.now(ZoneOffset.UTC).plusDays(30)
            SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, tenant)
                DSL
                    .using(connection, SQLDialect.POSTGRES)
                    .execute(
                        "insert into submission.period_due_date values (?, 'E-001', ?, ?::date, 1)",
                        tenant,
                        period,
                        due,
                    )
                connection.commit()
            }
        }

        /** The canonical event of [doc] at [version], shaped as canonical-version-created-payload.schema.json. */
        fun created(
            doc: String,
            version: Int,
            total: String,
            code: String,
            members: List<String> = emptyList(),
        ): Envelope {
            val data = createdJson(doc, version, total, code, members)
            schemaErrors(data).shouldBeEmpty()
            return envelope("com.dynokore.gst.canonical.CanonicalVersionCreated.v1", "/gst/canonical-svc", doc, data)
        }

        fun readyEvent(
            doc: String,
            version: Int,
        ): Envelope {
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val data =
                """{"docUuid":"$doc","version":$version,"docsequence":1,"submissionType":"2",
                |"specificationVersion":"1.0","payloadBytes":"${Base64.getEncoder().encodeToString(bytes)}",
                |"payloadSha256":"$sha"}
                """.trimMargin()
            return envelope("com.dynokore.gst.payload.PayloadReady.v1", "/gst/payload-svc", doc, data)
        }

        private fun envelope(
            type: String,
            source: String,
            doc: String,
            data: String,
        ) = Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = source,
            type = type,
            subject = doc,
            time = Instant.parse("2026-11-20T08:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E-001"),
            docUuid = DocUuid(UUID.fromString(doc)),
            transactionDate = LocalDate.parse("2026-03-20"),
            docSequence = 1,
            instanceId = "svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data.replace("\n", ""),
        )

        fun deliver(
            handler: EventHandler,
            event: Envelope,
        ): HandleOutcome = asTenant { handler.handle(event) }

        /** Version 1 of the original as its submission row once the payload is ready: READY, not queued. */
        fun seedReadyOriginal() {
            asTenant {
                dsl
                    .insertInto(Submission.SUBMISSION_)
                    .set(Submission.SUBMISSION_.TENANT_ID, tenant)
                    .set(Submission.SUBMISSION_.DOC_UUID, original)
                    .set(Submission.SUBMISSION_.VERSION, 1)
                    .set(Submission.SUBMISSION_.STATE, "READY")
                    .set(Submission.SUBMISSION_.PAYLOAD_BYTES, bytes)
                    .set(Submission.SUBMISSION_.PAYLOAD_SHA256, "a".repeat(SHA_LENGTH))
                    .set(Submission.SUBMISSION_.ENTITY_REF, "E-001")
                    .set(Submission.SUBMISSION_.ATTEMPT_COUNT, 0)
                    .execute()
            }
        }

        fun setOriginalState(state: String) {
            asTenant {
                dsl
                    .update(Submission.SUBMISSION_)
                    .set(Submission.SUBMISSION_.STATE, state)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(original))
                    .execute()
            }
        }

        fun states(doc: String): Map<Int, String> =
            asTenant {
                dsl
                    .select(Submission.SUBMISSION_.VERSION, Submission.SUBMISSION_.STATE)
                    .from(Submission.SUBMISSION_)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
                    .fetch()
                    .associate { it.value1()!! to it.value2()!! }
            }

        fun workRows(doc: String): Int =
            asTenant {
                dsl.fetchCount(WorkQueue.WORK_QUEUE, WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc))
            }

        fun requests(): List<OutboxRecord> =
            asTenant {
                dsl
                    .selectFrom(Outbox.OUTBOX)
                    .where(Outbox.OUTBOX.TYPE.eq(requestType))
                    .orderBy(Outbox.OUTBOX.ID)
                    .fetch()
                    .toList()
            }

        fun reason(doc: String): String? =
            asTenant {
                dsl
                    .select(Entry.OBLIGATION_REGISTER_ENTRY.NOT_SUBMITTABLE_REASON)
                    .from(Entry.OBLIGATION_REGISTER_ENTRY)
                    .where(Entry.OBLIGATION_REGISTER_ENTRY.DOC_UUID.eq(doc))
                    .fetchOne(Entry.OBLIGATION_REGISTER_ENTRY.NOT_SUBMITTABLE_REASON)
            }

        fun storedTotal(doc: String): BigDecimal? =
            asTenant {
                dsl
                    .select(Entry.OBLIGATION_REGISTER_ENTRY.TAXABLE_AMOUNT)
                    .from(Entry.OBLIGATION_REGISTER_ENTRY)
                    .where(Entry.OBLIGATION_REGISTER_ENTRY.DOC_UUID.eq(doc))
                    .fetchOne(Entry.OBLIGATION_REGISTER_ENTRY.TAXABLE_AMOUNT)
            }

        /** The values the second delivery must leave unchanged. */
        fun snapshot(): List<Any?> =
            listOf(
                reason(creditNote),
                workRows(creditNote),
                states(original),
                storedTotal(original),
                workRows(original),
                requests().size,
            )
    }

    private fun createdJson(
        doc: String,
        version: Int,
        total: String,
        code: String,
        members: List<String>,
    ): String {
        val extra = members.joinToString("") { ",$it" }
        val party = """{"name":"Party","identifierScheme":"SG_UEN","identifierValue":"201912345A"}"""
        val line =
            """{"lineNumber":1,"description":"Goods","quantity":1,"unitPrice":"$total","lineAmount":"$total",
            |"taxCode":"TX","taxAmount":"0.00"}
            """.trimMargin().replace("\n", "")
        return """{"docUuid":"$doc","version":$version,"docsequence":$version,"transactionDate":"2026-03-20",
            |"prescribedPeriodRef":"$period","scopeVerdict":"SUBMITTABLE","appliedPackVersions":[],
            |"traceReference":"trace-$doc",
            |"routingFacts":{"direction":"PURCHASE","networkPath":"NOT_ON_NETWORK"},
            |"canonicalDocument":{"documentNumber":{"value":"N-$doc","classification":"NONE"},
            |"documentTypeCode":{"value":"$code","classification":"NONE"},
            |"currency":{"value":"SGD","classification":"NONE"},
            |"parties":{"value":{"supplier":$party,"buyer":$party},"classification":"IDENTIFYING"},
            |"lines":{"value":[$line],"classification":"NONE"},
            |"taxSubtotals":{"value":[{"taxCode":"TX","taxableAmount":"$total","taxAmount":"0.00"}],
            |"classification":"NONE"},
            |"totals":{"value":{"totalExcludingTax":"$total","totalTax":"0.00","totalIncludingTax":"$total"},
            |"classification":"NONE"}}$extra}
            """.trimMargin().replace("\n", "")
    }

    private fun preceding(doc: String) = """"precedingDocUuid":"$doc""""

    private fun netMember(net: String) = """"netDerivation":$net"""

    private fun netOf(
        kit: Kit,
        version: Int = 1,
    ): String {
        val note = """{"docUuid":"${kit.creditNote}","version":1}"""
        return """{"kind":"SYSTEM_DERIVED","baseVersion":$version,"creditNotes":[$note]}"""
    }

    private fun schemaErrors(data: String): List<String> {
        val file =
            File("../submission-contract/build/contracts-repo/.bundle/canonical/schemas/")
                .resolve("canonical-version-created-payload.schema.json")
        check(file.isFile) { "the pinned payload schema is missing: $file" }
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return registry.getSchema(file.readText()).validate(data, InputFormat.JSON).map { it.message }
    }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    /** The original registered at version 1 (total 9000.00) with its READY row, then the credit note arrives. */
    private fun Kit.arriveOriginalAndCreditNote() {
        deliver(canonical, created(original, 1, "9000.00", "380"))
        seedReadyOriginal()
        deliver(canonical, created(creditNote, 1, "1000.00", "381", members = listOf(preceding(original))))
    }

    @Test
    fun `INV-AC-25366 an unsubmitted 9000 invoice and its 1000 credit note become one 8000 version and no credit note`(
        @TempDir dir: Path,
    ) = run(dir) {
        arriveOriginalAndCreditNote()
        // (1) one request naming the original, base version 1 and the credit note; the credit note is not yet decided
        val rows = requests()
        rows.size shouldBe 1
        val request = json.readTree(checkNotNull(rows.single().data).data())
        request.toString().contains(original) shouldBe true
        request.get("expectedBaseVersion").asInt() shouldBe 1
        request
            .get("creditNotes")
            .single()
            .get("docUuid")
            .asString() shouldBe creditNote
        reason(creditNote) shouldBe null
        // (2) the derived version 2 that canonical-svc publishes, then the payload of that version
        val derived = created(original, 2, "8000.00", "380", members = listOf(netMember(netOf(this))))
        deliver(canonical, derived) shouldBe HandleOutcome.APPLIED
        deliver(payloadReady, readyEvent(original, 2)) shouldBe HandleOutcome.APPLIED
        // (3) the credit note is netted, version 1 is cancelled and version 2 alone is submittable
        reason(creditNote) shouldBe "NETTED_BY_SYSTEM"
        workRows(creditNote) shouldBe 0
        states(original)[1] shouldBe "CANCELLED"
        states(original) shouldBe mapOf(1 to "CANCELLED", 2 to "QUEUED")
        storedTotal(original) shouldBe BigDecimal("8000.00")
        workRows(original) shouldBe 1
        // (4) a second delivery changes none of it
        val before = snapshot()
        deliver(canonical, derived)
        deliver(payloadReady, readyEvent(original, 2))
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-25366 a credit note without precedingDocUuid gives no request and is submitted as posted`(
        @TempDir dir: Path,
    ) = run(dir) {
        deliver(canonical, created(original, 1, "9000.00", "380"))
        seedReadyOriginal()
        deliver(canonical, created(creditNote, 1, "1000.00", "381"))
        requests().size shouldBe 0
        reason(creditNote) shouldBe null
        storedTotal(creditNote) shouldBe BigDecimal("1000.00")
        states(original) shouldBe mapOf(1 to "READY")
    }

    @Test
    fun `INV-AC-25366 an original already SENT gives no request and the credit note is submitted separately`(
        @TempDir dir: Path,
    ) = run(dir) {
        deliver(canonical, created(original, 1, "9000.00", "380"))
        seedReadyOriginal()
        setOriginalState("SENT")
        deliver(canonical, created(creditNote, 1, "1000.00", "381", members = listOf(preceding(original))))
        requests().size shouldBe 0
        reason(creditNote) shouldBe null
        storedTotal(creditNote) shouldBe BigDecimal("1000.00")
        states(original) shouldBe mapOf(1 to "SENT")
    }

    @Test
    fun `INV-AC-25366 a version 2 without netDerivation leaves the credit note decided again and not netted`(
        @TempDir dir: Path,
    ) = run(dir) {
        arriveOriginalAndCreditNote()
        deliver(canonical, created(original, 2, "9000.00", "380")) shouldBe HandleOutcome.APPLIED
        reason(creditNote) shouldBe null
        storedTotal(creditNote) shouldBe BigDecimal("1000.00")
        storedTotal(original) shouldBe BigDecimal("9000.00")
        requests().size shouldBe 1
    }

    private companion object {
        const val SHA_LENGTH = 64
    }
}
