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
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobTarget
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** The sweep of held credit notes over PostgreSQL, fired as the job `held-credit-note-sweep` (INV-AC-24523). */
class HeldCreditNoteSweepDbTest {
    private val actor = "svc:submission-svc"
    private val bytes = "<Invoice/>".toByteArray()

    private inner class Kit(
        context: ApplicationContext,
        val tenant: UUID = UUID.randomUUID(),
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val handler: EventHandler = context.getBean("payloadReadyHandler", EventHandler::class.java)
        val entries: RegisterEntryStore = context.getBean(RegisterEntryStore::class.java)

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
    }

    private fun run(
        dir: Path,
        block: (ApplicationContext, Kit, Kit) -> Unit,
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
            ).run { context -> block(context, Kit(context), Kit(context)) }
    }

    @Test
    fun `INV-AC-24523 firing the job twice releases C1 of T1 once into one queue item and leaves C5 of T2 held`(
        @TempDir dir: Path,
    ) = run(dir) { context, t1, t2 ->
        val p1 = UUID.randomUUID().toString()
        val c1 = UUID.randomUUID().toString()
        val c5 = UUID.randomUUID().toString()
        t1.register(p1, RowKind.INVOICE)
        t1.register(c1, RowKind.CREDIT_NOTE, preceding = p1)
        t2.register(c5, RowKind.CREDIT_NOTE, preceding = p1)
        t1.arrive(p1, 1)
        t1.arrive(c1, 1)
        t2.arrive(c5, 1)
        t1.setState(p1, "CANCELLED")
        t1.stateOf(c1, 1) shouldBe "AWAITING_PRECEDING"
        val runtime = context.getBean(JobRuntime::class.java)
        val first = Instant.now()

        runtime.fire("held-credit-note-sweep", first) shouldBe mapOf(JobTarget.Whole to FireResult.RAN)

        t1.stateOf(c1, 1) shouldBe "QUEUED"
        t1.lanes(c1) shouldBe listOf("LIVE")
        t1.codes(c1) shouldBe listOf("SUB-011")
        t2.stateOf(c5, 1) shouldBe "AWAITING_PRECEDING"
        t2.lanes(c5) shouldBe emptyList()
        // the lock of the first run is held for lockAtLeastFor (30 s, real clock): wait it out so the body runs again
        Thread.sleep(LOCK_LEASE_WAIT.toMillis())
        runtime.fire("held-credit-note-sweep", first.plusSeconds(60)) shouldBe
            mapOf(JobTarget.Whole to FireResult.RAN)
        t1.stateOf(c1, 1) shouldBe "QUEUED"
        t1.lanes(c1) shouldBe listOf("LIVE")
        t2.stateOf(c5, 1) shouldBe "AWAITING_PRECEDING"
    }

    @Test
    fun `INV-AC-24523 the runtime holds the definition held-credit-note-sweep and exactly five definitions`(
        @TempDir dir: Path,
    ) = run(dir) { context, _, _ ->
        val definitions = context.getBean(JobRuntime::class.java).registered()
        definitions.map { it.name }.toSet() shouldBe
            setOf(
                "backlog-evaluation",
                "compliance-evaluation",
                "unsubmitted-count-measurement",
                "waiting-sweep",
                "held-credit-note-sweep",
            )
        definitions.size shouldBe 5
        val definition = definitions.single { it.name == "held-credit-note-sweep" }
        definition shouldBe JobRuntimeConfiguration.HELD_CREDIT_NOTE_SWEEP
        definition.ownerService shouldBe "submission-svc"
        definition.entityScope shouldBe JobEntityScope.PLATFORM
        definition.schedule.cron shouldBe "0 * * * * *"
        definition.schedule.zone shouldBe ZoneOffset.UTC
        definition.criticality shouldBe JobCriticality.STANDARD
        definition.tolerance shouldBe Duration.ofMinutes(5)
    }

    @Test
    fun `INV-AC-24523 SUB-011 and SUB-012 are known to the generated catalogue at the pinned version`() {
        (ErrorCode.fromCode("SUB-011") != null) shouldBe true
        (ErrorCode.fromCode("SUB-012") != null) shouldBe true
    }

    private companion object {
        val LOCK_LEASE_WAIT: Duration = Duration.ofSeconds(31)
    }
}
