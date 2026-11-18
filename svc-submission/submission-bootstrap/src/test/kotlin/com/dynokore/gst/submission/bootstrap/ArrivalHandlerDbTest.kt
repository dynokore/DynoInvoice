package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.RaisedCode
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.filing.FilingFactStore
import com.dynokore.gst.submission.application.filing.NewFilingFact
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.PayloadReadyHandler
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.application.register.RegisterEntryStore
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.ListFacts
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
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.math.BigDecimal
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** The arrival handler over the real beans and a real database (INV-AC-21095). */
class ArrivalHandlerDbTest {
    private val actor = "svc:submission-svc"
    private val bytes = "<Invoice/>".toByteArray()
    private val unresolved = "submission_arrival_unresolved_input_total"
    private val unknownEntry = "submission_arrival_unknown_register_entry_total"
    private val codeType = "com.dynokore.gst.submission.DocumentCodeRaised.v1"

    private inner class Kit(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val registry: MeterRegistry = context.getBean(MeterRegistry::class.java)
        val handler: EventHandler = context.getBean("payloadReadyHandler", EventHandler::class.java)
        val entries: RegisterEntryStore = context.getBean(RegisterEntryStore::class.java)
        val filing: FilingFactStore = context.getBean(FilingFactStore::class.java)
        val store: SubmissionStore = context.getBean(SubmissionStore::class.java)
        val inFlight: InFlightStore = context.getBean(InFlightStore::class.java)
        val tenant: UUID = UUID.randomUUID()

        fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

        fun doc(): String = UUID.randomUUID().toString()

        @Suppress("LongParameterList") // the task rules the optional verdict, reason and preceding parameters
        fun register(
            doc: String,
            kind: RowKind,
            period: String = "2026-Q1",
            verdict: String? = null,
            reason: String? = null,
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
                    period,
                    LocalDate.of(2026, 4, 18),
                    true,
                    kind,
                    facts,
                    1,
                    scopeVerdict = verdict,
                    notSubmittableReason = reason,
                    precedingDocUuid = preceding,
                )
            asTenant { entries.upsert(TenantId(tenant), entry) }
        }

        fun file() =
            asTenant {
                filing.append(
                    NewFilingFact("E-001", "2026-Q1", LocalDate.of(2026, 4, 10), "A1", null, false, "u1"),
                )
            }

        fun arrive(
            doc: String,
            version: Int,
            rollback: Boolean = false,
        ) {
            val event = envelope(doc, version)
            asTenant {
                handler.handle(event)
                if (rollback) error("rolled back")
            }
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

        fun outboxRows(doc: String): Int =
            asTenant {
                dsl.fetchCount(
                    Outbox.OUTBOX,
                    Outbox.OUTBOX.DOC_UUID
                        .eq(UUID.fromString(doc))
                        .and(Outbox.OUTBOX.TYPE.eq(codeType)),
                )
            }

        fun submissionRows(doc: String): Int =
            asTenant { dsl.fetchCount(Submission.SUBMISSION_, Submission.SUBMISSION_.DOC_UUID.eq(doc)) }

        fun count(
            name: String,
            vararg tags: String,
        ): Double =
            registry
                .find(name)
                .tags(*tags)
                .counter()
                ?.count() ?: 0.0

        fun unresolvedTotal(): Double = registry.find(unresolved).counters().sumOf { it.count() }

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
    }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).also { it.file() }.block() }
    }

    @Test
    fun `INV-AC-21095 D1 first version of a filed period goes to DEADLINE_NEAR with SUB-010 stored and published`(
        @TempDir dir: Path,
    ) = run(dir) {
        val d1 = doc().also { register(it, RowKind.INVOICE) }
        arrive(d1, 1)
        lanes(d1) shouldBe listOf("DEADLINE_NEAR")
        codes(d1) shouldBe listOf("SUB-010")
        outboxRows(d1) shouldBe 1
    }

    @Test
    fun `INV-AC-21095 D2 a credit note raises SUB-010 only and counts the unresolved preceding pre-obligation`(
        @TempDir dir: Path,
    ) = run(dir) {
        val d2 = doc().also { register(it, RowKind.CREDIT_NOTE) }
        val before = count(unresolved, "input", "PRECEDING_PRE_OBLIGATION")
        arrive(d2, 1)
        lanes(d2) shouldBe listOf("DEADLINE_NEAR")
        codes(d2) shouldBe listOf("SUB-010")
        outboxRows(d2) shouldBe 1
        count(unresolved, "input", "PRECEDING_PRE_OBLIGATION") shouldBe before + 1.0
    }

    @Test
    fun `INV-AC-21095 D3 a document without a register entry goes to LIVE and is counted`(
        @TempDir dir: Path,
    ) = run(dir) {
        val d3 = doc()
        val before = count(unknownEntry)
        arrive(d3, 1)
        lanes(d3) shouldBe listOf("LIVE")
        codes(d3) shouldBe emptyList()
        outboxRows(d3) shouldBe 0
        count(unknownEntry) shouldBe before + 1.0
    }

    @Test
    fun `INV-AC-21095 version 2 of D1 goes to LIVE with no new code and no meter change`(
        @TempDir dir: Path,
    ) = run(dir) {
        val d1 = doc().also { register(it, RowKind.INVOICE) }
        arrive(d1, 1)
        asTenant {
            dsl
                .update(Submission.SUBMISSION_)
                .set(Submission.SUBMISSION_.STATE, "ACKNOWLEDGED")
                .where(Submission.SUBMISSION_.DOC_UUID.eq(d1))
                .execute()
            dsl.deleteFrom(WorkQueue.WORK_QUEUE).where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(d1)).execute()
        }
        val unresolvedBefore = unresolvedTotal()
        val unknownBefore = count(unknownEntry)
        arrive(d1, 2)
        lanes(d1) shouldBe listOf("LIVE")
        codes(d1) shouldBe listOf("SUB-010")
        outboxRows(d1) shouldBe 1
        unresolvedTotal() shouldBe unresolvedBefore
        count(unknownEntry) shouldBe unknownBefore
    }

    @Test
    fun `INV-AC-21095 D4 a first version of an unfiled period goes to LIVE with no code`(
        @TempDir dir: Path,
    ) = run(dir) {
        val d4 = doc().also { register(it, RowKind.INVOICE, "2026-Q2") }
        arrive(d4, 1)
        lanes(d4) shouldBe listOf("LIVE")
        codes(d4) shouldBe emptyList()
        outboxRows(d4) shouldBe 0
    }

    @Test
    fun `INV-AC-21095 D5 a rolled back handler leaves no item, no submission, no code and no outbox row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val d5 = doc().also { register(it, RowKind.INVOICE) }
        runCatching { arrive(d5, 1, rollback = true) }.isFailure shouldBe true
        lanes(d5) shouldBe emptyList()
        submissionRows(d5) shouldBe 0
        codes(d5) shouldBe emptyList()
        outboxRows(d5) shouldBe 0
    }

    @Test
    fun `INV-AC-21095 no arrival meter carries an entity, period or Doc UUID tag`(
        @TempDir dir: Path,
    ) = run(dir) {
        arrive(doc().also { register(it, RowKind.CREDIT_NOTE) }, 1)
        arrive(doc(), 1)
        val meters = registry.meters.filter { it.id.name.startsWith("submission_arrival_") }
        meters.map { it.id.name }.toSet() shouldBe setOf(unresolved, unknownEntry)
        meters.flatMap { m -> m.id.tags.map { it.key } }.toSet() shouldBe setOf("input")
    }

    private data class ScopeDocs(
        val c1: String,
        val c2: String,
        val i1: String,
        val c3: String,
    )

    private fun Kit.scopePairs(): ScopeDocs {
        val p1 = doc().also { register(it, RowKind.INVOICE, verdict = "NOT_SUBMITTABLE") }
        val p2 = doc().also { register(it, RowKind.INVOICE, verdict = "SUBMITTABLE") }
        val c1 = doc().also { register(it, RowKind.CREDIT_NOTE, verdict = "SUBMITTABLE", preceding = p1) }
        val c2 = doc().also { register(it, RowKind.CREDIT_NOTE, verdict = "SUBMITTABLE", preceding = p2) }
        val i1 = doc().also { register(it, RowKind.INVOICE, verdict = "SUBMITTABLE", preceding = p1) }
        return ScopeDocs(c1, c2, i1, doc())
    }

    @Test
    fun `INV-AC-24505 C1 a disagreeing credit note ends BLOCKED with one SUB-013 and no queue item`(
        @TempDir dir: Path,
    ) = run(dir) {
        val c1 = scopePairs().c1
        val outcome = asTenant { handler.handle(envelope(c1, 1)) }
        outcome shouldBe HandleOutcome.APPLIED
        stateOf(c1, 1) shouldBe "BLOCKED"
        codes(c1) shouldBe listOf("SUB-013")
        outboxRows(c1) shouldBe 1
        lanes(c1) shouldBe emptyList()
        asTenant { inFlight.waitingDocUuids(TenantId(tenant), 100) }.contains(c1) shouldBe false
        asTenant { store.moveReadyToQueued(TenantId(tenant), c1, 1) } shouldBe 0
        stateOf(c1, 1) shouldBe "BLOCKED"
    }

    @Test
    fun `INV-AC-24505 C1 a later version of the disagreeing credit note is blocked too`(
        @TempDir dir: Path,
    ) = run(dir) {
        val c1 = scopePairs().c1
        arrive(c1, 1)
        arrive(c1, 2)
        stateOf(c1, 2) shouldBe "BLOCKED"
        lanes(c1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24505 C2 an agreeing credit note is queued as before with no SUB-013`(
        @TempDir dir: Path,
    ) = run(dir) {
        val c2 = scopePairs().c2
        arrive(c2, 1)
        stateOf(c2, 1) shouldBe "QUEUED"
        lanes(c2).size shouldBe 1
        codes(c2).contains("SUB-013") shouldBe false
    }

    @Test
    fun `INV-AC-24505 I1 and C3 end as before this change`(
        @TempDir dir: Path,
    ) = run(dir) {
        val docs = scopePairs()
        val i1 = docs.i1
        val c3 = docs.c3
        arrive(i1, 1)
        arrive(c3, 1)
        for (d in listOf(i1, c3)) {
            stateOf(d, 1) shouldBe "QUEUED"
            lanes(d).size shouldBe 1
            codes(d).contains("SUB-013") shouldBe false
        }
    }

    @Test
    fun `INV-AC-24505 a handler call rolled back after the check leaves no BLOCKED row, code or outbox row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val c1 = scopePairs().c1
        runCatching { arrive(c1, 1, rollback = true) }.isFailure shouldBe true
        submissionRows(c1) shouldBe 0
        codes(c1) shouldBe emptyList()
        outboxRows(c1) shouldBe 0
        lanes(c1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24505 the handler constructor holds no parameter type named Transport`() {
        PayloadReadyHandler::class.java.constructors
            .flatMap { c -> c.parameterTypes.map { it.name } }
            .none { it.contains("Transport") } shouldBe true
    }
}
