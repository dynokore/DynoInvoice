package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.adapters.SignedBindings
import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.RaisedCode
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.drain.SafetyMargins
import com.dynokore.gst.submission.application.ordering.CreditNoteOptions
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SubmissionWorker
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.application.register.RegisterEntryStore
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.math.BigDecimal
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** The arrival hold of a credit note through the real handler over PostgreSQL (INV-AC-24521). */
class CreditNoteHoldDbTest {
    private val actor = "svc:submission-svc"
    private val bytes = "<Invoice/>".toByteArray()

    private inner class Kit(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val handler: EventHandler = context.getBean("payloadReadyHandler", EventHandler::class.java)
        val ack: EventHandler = context.getBean("acknowledgementReceivedHandler", EventHandler::class.java)
        val worker: SubmissionWorker = context.getBean(SubmissionWorker::class.java)
        val workQueue: WorkQueuePort = context.getBean(WorkQueuePort::class.java)
        val entries: RegisterEntryStore = context.getBean(RegisterEntryStore::class.java)
        val tenant: UUID = UUID.randomUUID()

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
                        "insert into submission.period_due_date values (?, 'E-001', '2026-Q1', ?::date, 1)",
                        tenant,
                        due,
                    )
                connection.commit()
            }
        }

        fun register(
            doc: String,
            kind: RowKind,
            preceding: String? = null,
        ) {
            val facts =
                ListFacts(
                    "N-1",
                    "380",
                    Direction.SALE,
                    "Acme",
                    "201912345A",
                    BigDecimal("1.00"),
                    BigDecimal("0.09"),
                    emptySet(),
                )
            val entry =
                RegisterEntry(
                    doc,
                    "E-001",
                    "2026-Q1",
                    LocalDate.of(2026, 4, 18),
                    true,
                    kind,
                    facts,
                    1,
                    precedingDocUuid = preceding,
                )
            asTenant { entries.upsert(TenantId(tenant), entry) }
        }

        fun arrive(
            doc: String,
            version: Int,
        ): HandleOutcome {
            val event = envelope(doc, version)
            return asTenant { handler.handle(event) }
        }

        fun stateOf(
            doc: String,
            version: Int,
        ): String? =
            asTenant {
                dsl
                    .select(Submission.SUBMISSION_.STATE)
                    .from(Submission.SUBMISSION_)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
                    .and(Submission.SUBMISSION_.VERSION.eq(version))
                    .fetchOne()
                    ?.value1()
            }

        fun setState(
            doc: String,
            state: String,
        ) {
            asTenant {
                dsl
                    .update(Submission.SUBMISSION_)
                    .set(Submission.SUBMISSION_.STATE, state)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
                    .execute()
            }
        }

        fun lanes(doc: String): List<String> =
            asTenant {
                dsl
                    .select(WorkQueue.WORK_QUEUE.LANE)
                    .from(WorkQueue.WORK_QUEUE)
                    .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc))
                    .fetch()
                    .map { it.value1().toString() }
            }

        fun codes(doc: String): List<String> =
            asTenant {
                dsl
                    .select(RaisedCode.RAISED_CODE.CODE)
                    .from(RaisedCode.RAISED_CODE)
                    .where(RaisedCode.RAISED_CODE.DOC_UUID.eq(doc))
                    .orderBy(RaisedCode.RAISED_CODE.SEQ)
                    .fetch()
                    .map { it.value1()!! }
            }

        fun envelope(
            doc: String,
            version: Int,
        ): Envelope {
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            return Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/payload-svc",
                type = "com.dynokore.gst.payload.PayloadReady.v1",
                subject = doc,
                time = Instant.parse("2026-10-19T08:00:00Z"),
                tenant = TenantId(tenant),
                entity = EntityRef("E-001"),
                docUuid = DocUuid(UUID.fromString(doc)),
                transactionDate = LocalDate.parse("2026-10-19"),
                docSequence = null,
                instanceId = "payload-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"docUuid":"$doc","version":$version,"docsequence":1,"submissionType":"2",
                    |"specificationVersion":"1.0","payloadBytes":"${Base64.getEncoder().encodeToString(bytes)}",
                    |"payloadSha256":"$sha"}
                    """.trimMargin(),
            )
        }

        /** Drops every queued item: `claimNext` is cross-tenant, so left-overs would be claimed by the polls. */
        fun drain() {
            while (true) {
                val item = workQueue.claimNext() ?: break
                tenants.bind(item.tenant, actor) { runner.inTransaction { workQueue.complete(item) } }
            }
        }

        fun acknowledge(
            doc: String,
            version: Int,
        ): HandleOutcome {
            val event =
                envelope(doc, version).copy(
                    source = "/gst/transport-svc",
                    type = "com.dynokore.gst.transport.AcknowledgementReceived.v1",
                    dataJson =
                        """{"adapter":"a","apNotificationId":"N-1","docUuid":"$doc","version":$version,""" +
                            """"acknowledgementId":"ACK-$doc"}""",
                )
            return asTenant { ack.handle(event) }
        }

        /** An original P1 that has arrived (QUEUED) and a credit note CN1 over it that has not. */
        fun pair(): Pair<String, String> {
            val p1 = UUID.randomUUID().toString()
            val cn1 = UUID.randomUUID().toString()
            register(p1, RowKind.INVOICE)
            register(cn1, RowKind.CREDIT_NOTE, preceding = p1)
            arrive(p1, 1)
            stateOf(p1, 1) shouldBe "QUEUED"
            return p1 to cn1
        }
    }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        val margins = SafetyMargins { _, _, _ -> SafetyMarginAnswer.Known(checkNotNull(PackMargin.of(2, "DAYS"))) }
        SubmissionContext
            .online(dir)
            .withAllowBeanDefinitionOverriding(true)
            .withBean("safetyMargins", SafetyMargins::class.java, { margins })
            .withBean(
                "creditNoteOptions",
                CreditNoteOptions::class.java,
                { CreditNoteOptions { _, _, _ -> CreditNoteOption.RELEASE_ALONE } },
            ).run { context -> Kit(context).block() }
    }

    @Test
    fun `INV-AC-24521 the catalogue holds SUB-011 and SUB-012 at the pinned version`() {
        (ErrorCode.fromCode("SUB-011") != null) shouldBe true
        (ErrorCode.fromCode("SUB-012") != null) shouldBe true
    }

    @Test
    fun `INV-AC-24521 a credit note over a QUEUED original is AWAITING_PRECEDING with one SUB-011 and no queue item`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (_, cn1) = pair()
        arrive(cn1, 1) shouldBe HandleOutcome.APPLIED
        stateOf(cn1, 1) shouldBe "AWAITING_PRECEDING"
        codes(cn1).count { it == "SUB-011" } shouldBe 1
        lanes(cn1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 a credit note over an ACKNOWLEDGED original is QUEUED in lane LIVE with no SUB-011`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (p1, cn1) = pair()
        setState(p1, "ACKNOWLEDGED")
        arrive(cn1, 1)
        stateOf(cn1, 1) shouldBe "QUEUED"
        lanes(cn1) shouldBe listOf("LIVE")
        codes(cn1).contains("SUB-011") shouldBe false
    }

    @Test
    fun `INV-AC-24521 a repeat delivery of the held version changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (_, cn1) = pair()
        arrive(cn1, 1)
        arrive(cn1, 1) shouldBe HandleOutcome.IGNORED
        stateOf(cn1, 1) shouldBe "AWAITING_PRECEDING"
        codes(cn1).count { it == "SUB-011" } shouldBe 1
        lanes(cn1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24521 version 2 of a held credit note is not held`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (_, cn1) = pair()
        arrive(cn1, 1)
        arrive(cn1, 2)
        // INV-REQ-573: the newer version cancels the held, unclaimed version 1
        stateOf(cn1, 1) shouldBe "CANCELLED"
        (stateOf(cn1, 2) != "AWAITING_PRECEDING") shouldBe true
        stateOf(cn1, 2) shouldBe "QUEUED"
        codes(cn1).count { it == "SUB-011" } shouldBe 1
    }

    @Test
    fun `INV-AC-24521 a handler call rolled back after the hold leaves no row, code or hold`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (_, cn1) = pair()
        val event = envelope(cn1, 1)
        runCatching { asTenant { handler.handle(event).also { error("rolled back") } } }.isFailure shouldBe true
        stateOf(cn1, 1) shouldBe null
        codes(cn1) shouldBe emptyList()
        lanes(cn1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1827 workers polling while a credit note is AWAITING_PRECEDING never claim it and claim it once it is QUEUED`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (p1, cn1) = pair()
        arrive(cn1, 1) shouldBe HandleOutcome.APPLIED
        stateOf(cn1, 1) shouldBe "AWAITING_PRECEDING"
        setState(p1, "SENT")
        drain()

        repeat(POLLS) {
            worker.takeNext() shouldBe null
            stateOf(cn1, 1) shouldBe "AWAITING_PRECEDING"
            lanes(cn1).size shouldBe 0
        }

        acknowledge(p1, 1) shouldBe HandleOutcome.APPLIED
        stateOf(p1, 1) shouldBe "ACKNOWLEDGED"
        stateOf(cn1, 1) shouldBe "QUEUED"
        lanes(cn1) shouldBe listOf("LIVE")

        val result = worker.takeNext() as? SentResult.Sent ?: error("expected the credit note to be claimed")
        result.dispatch.docUuid shouldBe cn1
        result.dispatch.version shouldBe 1
        stateOf(cn1, 1) shouldBe "SENT"
    }

    private companion object {
        const val POLLS = 5
    }
}
