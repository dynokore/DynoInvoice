package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.application.register.RegisterEntryStore
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/**
 * INV-REQ-573 (INV-AC-609): a newer version reaching READY cancels the older unclaimed version, and the stale queue
 * item is dropped at the despatch step. Real database, real handler and worker beans, a recording fake transport.
 */
class SupersedeBeforeTransmissionDbTest {
    private val accepted = TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray())

    private inner class Kit(
        context: ApplicationContext,
    ) {
        val f = OutcomeFixture(context)
        val handler: EventHandler = context.getBean("payloadReadyHandler", EventHandler::class.java)
        val entries: RegisterEntryStore = context.getBean(RegisterEntryStore::class.java)
        val tenant: UUID = UUID.randomUUID()

        fun doc(): String = UUID.randomUUID().toString()

        fun arrive(
            doc: String,
            version: Int,
            payload: ByteArray = "<Invoice v$version/>".toByteArray(),
        ) {
            f.asTenant(tenant) { handler.handle(envelope(doc, version, payload)) }
        }

        fun state(
            doc: String,
            version: Int,
        ): String = f.stateOf(tenant, doc, version)

        /** Seeds version 1 of [doc] in [state]. */
        fun seed(
            doc: String,
            state: LifecycleState,
        ) = f.seed(tenant, doc, state)

        fun despatchedRowsNaming(
            doc: String,
            version: Int,
        ): Int =
            f.asTenant(tenant) {
                f.dsl
                    .selectFrom(Outbox.OUTBOX)
                    .where(Outbox.OUTBOX.DOC_UUID.eq(UUID.fromString(doc)))
                    .and(Outbox.OUTBOX.TYPE.eq("com.dynokore.gst.submission.SubmissionDespatched.v1"))
                    .fetch()
                    .count { checkNotNull(it.data).data().replace(" ", "").contains("\"version\":$version,") }
            }

        fun register(
            doc: String,
            verdict: String?,
            preceding: String?,
            kind: RowKind,
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
                    scopeVerdict = verdict,
                    precedingDocUuid = preceding,
                )
            f.asTenant(tenant) { entries.upsert(TenantId(tenant), entry) }
        }

        fun envelope(
            doc: String,
            version: Int,
            payload: ByteArray,
        ): Envelope {
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
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
                    |"specificationVersion":"1.0","payloadBytes":"${Base64.getEncoder().encodeToString(payload)}",
                    |"payloadSha256":"$sha"}
                    """.trimMargin(),
            )
        }
    }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    @Test
    fun `INV-AC-609 version 2 arriving while version 1 is QUEUED and unclaimed cancels version 1 and only version 2 is despatched`(
        @TempDir dir: Path,
    ) = run(dir) {
        val u = doc()
        arrive(u, 1)
        state(u, 1) shouldBe "QUEUED"
        f.itemStates(tenant, u).size shouldBe 1

        arrive(u, 2, "<Other/>".toByteArray())

        state(u, 1) shouldBe "CANCELLED"
        state(u, 2) shouldBe "QUEUED"
        val transport = FakeTransport(accepted)
        val cycle = f.cycle(transport)
        cycle.runOnceItem() shouldBe DespatchedVersion(u, 2)
        cycle.runOnceItem() shouldBe null
        transport.requests.size shouldBe 1
        transport.requests[0].version shouldBe 2
        f.attempts(tenant, u).count { it.version == 1 } shouldBe 0
        despatchedRowsNaming(u, 1) shouldBe 0
        despatchedRowsNaming(u, 2) shouldBe 1
        // a completed item leaves no row: nothing unfinished is left for U
        f.itemStates(tenant, u) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-609 version 1 in READY, AWAITING_PRECEDING or QUEUED is cancelled by version 2`(
        @TempDir dir: Path,
    ) = run(dir) {
        for (s in listOf(LifecycleState.READY, LifecycleState.AWAITING_PRECEDING, LifecycleState.QUEUED)) {
            val u = doc()
            seed(u, s)
            arrive(u, 2)
            state(u, 1) shouldBe "CANCELLED"
        }
    }

    @Test
    fun `INV-AC-609 version 1 in any other state keeps it and in flight states version 2 waits`(
        @TempDir dir: Path,
    ) = run(dir) {
        val keep =
            listOf(
                LifecycleState.SENT,
                LifecycleState.SENT_UNCONFIRMED,
                LifecycleState.RETRY,
                LifecycleState.ACKNOWLEDGED,
                LifecycleState.REJECTED,
                LifecycleState.DEAD_LETTERED,
                LifecycleState.SUPERSEDED,
            )
        val inFlight = setOf(LifecycleState.SENT, LifecycleState.SENT_UNCONFIRMED, LifecycleState.RETRY)
        for (s in keep) {
            val u = doc()
            seed(u, s)
            arrive(u, 2)
            state(u, 1) shouldBe s.name
            if (s in inFlight) {
                state(u, 2) shouldBe "READY"
                f.itemStates(tenant, u) shouldBe emptyList()
            }
        }
    }

    @Test
    fun `INV-AC-609 a smaller version arriving after version 2 QUEUED leaves version 2 QUEUED`(
        @TempDir dir: Path,
    ) = run(dir) {
        val u = doc()
        arrive(u, 2)
        state(u, 2) shouldBe "QUEUED"
        arrive(u, 1)
        state(u, 2) shouldBe "QUEUED"
    }

    @Test
    fun `INV-AC-609 a claimed version 1 cancelled by version 2 is discarded and its item completed`(
        @TempDir dir: Path,
    ) = run(dir) {
        val u = doc()
        arrive(u, 1)
        val item = checkNotNull(f.workQueue.claimNext()) { "no claimable item" }
        arrive(u, 2)
        state(u, 1) shouldBe "CANCELLED"

        val result = f.sent.perform(item, setOf(LifecycleState.QUEUED, LifecycleState.RETRY))

        result.shouldBeInstanceOf<SentResult.Discarded>()
        f.itemStates(tenant, u).size shouldBe 1 // only the item of version 2 is left
        f.attempts(tenant, u).count { it.version == 1 } shouldBe 0
        despatchedRowsNaming(u, 1) shouldBe 0
        state(u, 1) shouldBe "CANCELLED"
    }

    @Test
    fun `INV-AC-609 a second delivery of version 2 changes no state and writes no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val u = doc()
        arrive(u, 1)
        arrive(u, 2)
        val before = Triple(state(u, 1), state(u, 2), f.itemStates(tenant, u))
        val rows = f.rows(tenant, u)

        arrive(u, 2)

        Triple(state(u, 1), state(u, 2), f.itemStates(tenant, u)) shouldBe before
        f.rows(tenant, u) shouldBe rows
    }

    @Test
    fun `INV-AC-609 a newer version blocked by the scope check cancels nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val p1 = doc().also { register(it, "NOT_SUBMITTABLE", null, RowKind.INVOICE) }
        val c1 = doc().also { register(it, "SUBMITTABLE", p1, RowKind.CREDIT_NOTE) }
        seed(c1, LifecycleState.QUEUED)

        arrive(c1, 2)

        state(c1, 2) shouldBe "BLOCKED"
        state(c1, 1) shouldBe "QUEUED"
    }

    @Test
    fun `INV-AC-609 a handler call rolled back after the cancel leaves version 1 as seeded`(
        @TempDir dir: Path,
    ) = run(dir) {
        val u = doc()
        seed(u, LifecycleState.QUEUED)
        runCatching {
            f.asTenant(tenant) {
                handler.handle(envelope(u, 2, "<Invoice v2/>".toByteArray()))
                error("rolled back")
            }
        }.isFailure shouldBe true
        state(u, 1) shouldBe "QUEUED"
        f.rows(tenant, u) shouldBe 1
    }
}
